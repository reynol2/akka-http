/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.dispatch.ExecutionContexts
import akka.event.LoggingAdapter
import akka.http.impl.engine.http2.{ AlpnSwitch, Http2AlpnSupport, Http2Blueprint }
import akka.http.impl.engine.server.MasterServerTerminator
import akka.http.impl.util.LogByteStringTools.logTLSBidiBySetting
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.UseHttp2.{ Negotiated, Never }
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.TLSProtocol.{ SendBytes, SessionBytes, SslTlsInbound, SslTlsOutbound }
import akka.stream.scaladsl.{ BidiFlow, Flow, Keep, Sink, TLS, Tcp }
import akka.stream.{ IgnoreComplete, Materializer }
import akka.util.ByteString
import akka.{ Done, NotUsed }
import com.typesafe.config.Config
import javax.net.ssl.SSLEngine

import scala.concurrent.Future
import scala.util.Success
import scala.util.control.NonFatal

/** Entry point for Http/2 server */
final class Http2Ext(private val config: Config)(implicit val system: ActorSystem)
  extends akka.actor.Extension {
  // FIXME: won't having the same package as top-level break osgi?

  private[this] final val DefaultPortForProtocol = -1 // any negative value

  val http = Http(system)

  /**
   * Handle requests using HTTP/2 immediately, without any TLS or negotiation layer.
   */
  private def bindAndHandleWithoutNegotiation(
    handler:     HttpRequest ⇒ Future[HttpResponse],
    interface:   String,
    port:        Int,
    settings:    ServerSettings,
    parallelism: Int,
    log:         LoggingAdapter)(implicit fm: Materializer): Future[ServerBinding] = {
    if (parallelism == 1)
      log.warning("HTTP/2 `bindAndHandleAsync` was called with default parallelism = 1. This means that request handling " +
        "concurrency per connection is disabled. This is likely not what you want with HTTP/2.")

    val effectivePort = if (port >= 0) port else 80

    val serverLayer: Flow[ByteString, ByteString, Future[Done]] = Flow.fromGraph(
      Flow[HttpRequest]
        .watchTermination()(Keep.right)
        // FIXME: parallelism should maybe kept in track with SETTINGS_MAX_CONCURRENT_STREAMS so that we don't need
        // to buffer requests that cannot be handled in parallel
        .via(Http2Blueprint.handleWithStreamIdHeader(parallelism)(handler)(system.dispatcher))
        .joinMat(Http2Blueprint.serverStack(settings, log))(Keep.left))

    val connections = Tcp().bind(interface, effectivePort, settings.backlog, settings.socketOptions, halfClose = false, settings.timeouts.idleTimeout)

    val masterTerminator = new MasterServerTerminator(log)

    connections.mapAsyncUnordered(settings.maxConnections) {
      incoming: Tcp.IncomingConnection ⇒
        try {
          serverLayer.addAttributes(Http.prepareAttributes(settings, incoming)).joinMat(incoming.flow)(Keep.left)
            .run().recover {
              // Ignore incoming errors from the connection as they will cancel the binding.
              // As far as it is known currently, these errors can only happen if a TCP error bubbles up
              // from the TCP layer through the HTTP layer to the Http.IncomingConnection.flow.
              // See https://github.com/akka/akka/issues/17992
              case NonFatal(ex) ⇒
                Done
            }(ExecutionContexts.sameThreadExecutionContext)
        } catch {
          case NonFatal(e) ⇒
            log.error(e, "Could not materialize handling flow for {}", incoming)
            throw e
        }
    }.mapMaterializedValue {
      _.map(tcpBinding ⇒ ServerBinding(tcpBinding.localAddress)(
        () ⇒ tcpBinding.unbind(),
        timeout ⇒ masterTerminator.terminate(timeout)(fm.executionContext)
      ))(fm.executionContext)
    }.to(Sink.ignore).run()
  }

  def bindAndHandleAsync(
    handler:   HttpRequest ⇒ Future[HttpResponse],
    interface: String, port: Int = DefaultPortForProtocol,
    connectionContext: ConnectionContext,
    settings:          ServerSettings    = ServerSettings(system),
    parallelism:       Int               = 1,
    log:               LoggingAdapter    = system.log)(implicit fm: Materializer): Future[ServerBinding] = {
    // TODO: split up similarly to what `Http` does into `serverLayer`, `bindAndHandle`, etc.
    require(connectionContext.http2 != Never)

    if (connectionContext.isSecure) {
      bindAndHandleAsync(handler, interface, port, connectionContext.asInstanceOf[HttpsConnectionContext], settings, parallelism, log)
    } else {
      if (connectionContext.http2 == Negotiated)
        // https://github.com/akka/akka-http/issues/1966
        throw new NotImplementedError("h2c not supported")
      else
        bindAndHandleWithoutNegotiation(handler, interface, port, settings, parallelism, log)
    }
  }

  private def bindAndHandleAsync(
    handler:   HttpRequest ⇒ Future[HttpResponse],
    interface: String, port: Int,
    httpsContext: HttpsConnectionContext,
    settings:     ServerSettings,
    parallelism:  Int,
    log:          LoggingAdapter)(implicit fm: Materializer): Future[ServerBinding] = {
    // automatically preserves association between request and response by setting the right headers, can use mapAsyncUnordered

    val effectivePort = if (port >= 0) port else 443

    def http2Layer(): BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, NotUsed] =
      Http2Blueprint.serverStack(settings, log) atop
        unwrapTls atop
        logTLSBidiBySetting("server-plain-text", settings.logUnencryptedNetworkBytes)

    // Flow is not reusable because we need a side-channel to transport the protocol
    // chosen by ALPN from the SSLEngine to the switching stage
    def serverLayer(): BidiFlow[HttpResponse, ByteString, ByteString, HttpRequest, NotUsed] = {
      // Mutable cell to transport the chosen protocol from the SSLEngine to
      // the switch stage.
      // Doesn't need to be volatile because there's a happens-before relationship (enforced by memory barriers)
      // between the SSL handshake and sending out the first SessionBytes, and receiving the first SessionBytes
      // and reading out the variable.
      var chosenProtocol: Option[String] = None
      def setChosenProtocol(protocol: String): Unit =
        if (chosenProtocol.isEmpty) chosenProtocol = Some(protocol)
        else throw new IllegalStateException("ChosenProtocol was set twice. Http2.serverLayer is not reusable.")
      def getChosenProtocol(): String = chosenProtocol.getOrElse("h1") // default to http/1, e.g. when ALPN jar is missing

      var eng: Option[SSLEngine] = None
      def createEngine(): SSLEngine = {
        val engine = httpsContext.sslContext.createSSLEngine()
        eng = Some(engine)
        engine.setUseClientMode(false)
        Http2AlpnSupport.applySessionParameters(engine, httpsContext.firstSession)
        Http2AlpnSupport.enableForServer(engine, setChosenProtocol)
      }
      val tls = TLS(() ⇒ createEngine, _ ⇒ Success(()), IgnoreComplete)

      val removeEngineOnTerminate: BidiFlow[ByteString, ByteString, ByteString, ByteString, NotUsed] = {
        implicit val ec = fm.executionContext
        BidiFlow.fromFlows(
          Flow[ByteString],
          Flow[ByteString]
            .watchTermination()((n, fd) ⇒ {
              fd.onComplete(_ ⇒ eng.foreach(Http2AlpnSupport.cleanupForServer))
              n
            })
        )
      }

      AlpnSwitch(() ⇒ getChosenProtocol, http.serverLayer(settings, None, log), http2Layer()) atop
        tls atop removeEngineOnTerminate
    }

    // Not reusable, see above.
    def fullLayer(): Flow[ByteString, ByteString, Future[Done]] = Flow.fromGraph(
      Flow[HttpRequest]
        .watchTermination()(Keep.right)
        // FIXME: parallelism should maybe kept in track with SETTINGS_MAX_CONCURRENT_STREAMS so that we don't need
        // to buffer requests that cannot be handled in parallel
        .via(Http2Blueprint.handleWithStreamIdHeader(parallelism)(handler)(system.dispatcher))
        .joinMat(serverLayer())(Keep.left))

    val connections = Tcp().bind(interface, effectivePort, settings.backlog, settings.socketOptions, halfClose = false, settings.timeouts.idleTimeout)

    val masterTerminator = new MasterServerTerminator(log)

    connections.mapAsyncUnordered(settings.maxConnections) {
      incoming: Tcp.IncomingConnection ⇒
        try {
          fullLayer().addAttributes(Http.prepareAttributes(settings, incoming)).joinMat(incoming.flow)(Keep.left)
            .run().recover {
              // Ignore incoming errors from the connection as they will cancel the binding.
              // As far as it is known currently, these errors can only happen if a TCP error bubbles up
              // from the TCP layer through the HTTP layer to the Http.IncomingConnection.flow.
              // See https://github.com/akka/akka/issues/17992
              case NonFatal(ex) ⇒
                Done
            }(ExecutionContexts.sameThreadExecutionContext)
        } catch {
          case NonFatal(e) ⇒
            log.error(e, "Could not materialize handling flow for {}", incoming)
            throw e
        }
    }.mapMaterializedValue {
      _.map(tcpBinding ⇒ ServerBinding(tcpBinding.localAddress)(
        () ⇒ tcpBinding.unbind(),
        timeout ⇒ masterTerminator.terminate(timeout)(fm.executionContext)
      ))(fm.executionContext)
    }.to(Sink.ignore).run()
  }

  private val unwrapTls: BidiFlow[ByteString, SslTlsOutbound, SslTlsInbound, ByteString, NotUsed] =
    BidiFlow.fromFlows(Flow[ByteString].map(SendBytes), Flow[SslTlsInbound].collect {
      case SessionBytes(_, bytes) ⇒ bytes
    })

}

object Http2 extends ExtensionId[Http2Ext] with ExtensionIdProvider {
  override def get(system: ActorSystem): Http2Ext = super.get(system)
  def apply()(implicit system: ActorSystem): Http2Ext = super.apply(system)
  def lookup(): ExtensionId[_ <: Extension] = Http2
  def createExtension(system: ExtendedActorSystem): Http2Ext =
    new Http2Ext(system.settings.config getConfig "akka.http")(system)
}
