/*
 * Copyright 2014 Frugal Mechanic (http://frugalmechanic.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fm.http.client

import fm.common.Logging
import fm.http._
import io.netty.handler.codec.http.{HttpHeaders, HttpResponse, HttpVersion}
import java.io.Closeable
import scala.concurrent.{ExecutionContext, Future, Promise}

object Response {
  def apply(response: HttpResponse, content: LinkedHttpContentReader)(implicit execution: ExecutionContext): AsyncResponse = new AsyncResponse(response, content)
}

sealed abstract class Response(response: HttpResponse) extends Closeable {
  val status: Status = Status(response.getStatus())
  
  val version: HttpVersion = response.getProtocolVersion()
  
  val headers: ImmutableHeaders = ImmutableHeaders(response.headers)
  
  /**
   * Any Set-Cookie Headers are decoded into Cookies
   */
  val cookies: Vector[Cookie] = headers.setCookies
  
  /**
   * The Content-Length of the request body (if known)
   */
  def contentLength: Option[Long] = Option(HttpHeaders.getContentLength(response, -1)).filter{ _ >= 0 }
  
  override def toString: String = {
    s"${version.text} ${status.code} ${status.msg}\n\n$headers"
  }
}

/**
 * Represents a response where we have the FULL body (as a string)
 */
final class FullResponse(response: HttpResponse, val body: String) extends Response(response) {  
  def close(): Unit = { }
}

object AsyncResponse {
  private def expectBodyContent(response: HttpResponse): Boolean = {
    val hasContentLength: Boolean = HttpHeaders.getContentLength(response, -1) > 0
    val isChunked: Boolean = HttpHeaders.isTransferEncodingChunked(response)
    hasContentLength || isChunked
  }
}

/**
 * Represents a Response where the body of the response can be read asynchronously (which means it can be much larger than available heap)
 */
final class AsyncResponse (response: HttpResponse, content: LinkedHttpContentReader)(implicit execution: ExecutionContext) extends Response(response) with Logging {
  
  /**
   * Should this response contain a non-empty body?
   */
  val hasBody: Boolean = AsyncResponse.expectBodyContent(response)
  
  /**
   * If there is no body then the promise starts completed
   */
  private[this] val completedPromise: Promise[Unit] = if (!hasBody) Promise.successful(()) else Promise()
  
  /**
   * This future is completed when the request has been fully processed
   */
  def completed: Future[Unit] = completedPromise.future

  if (!hasBody) requireEmptyContent()
  
  private def requireEmptyContent(): Unit = {
    content.foldLeft(false){ (isSet, buf) =>
      if (isSet) logger.error("Expected EmptyContent for request: "+this)
      true
    }
  }
  
  val body: Option[LinkedHttpContentReader] = if (hasBody) Some(content) else None

  def toFullResponse(maxLength: Long = Long.MaxValue): Future[FullResponse] = body match {
    case None         => Future.successful(new FullResponse(response, ""))
    case Some(reader) => reader.readToString(maxLength).map{ new FullResponse(response, _) }
  }
  
  def readBodyToString(maxLength: Long = Long.MaxValue): Future[String] = {
    val f = body.map{ _.readToString(maxLength) }.getOrElse{ Future.successful("") }
    f.onComplete{ case _ => close() }
    f
  }
  
  def isContentFullyRead: Boolean = if (hasBody) content.isFullyRead else true
  
  def close(): Unit = close(null)
  
  def close(t: Throwable): Unit = {
    body.foreach{ _.close() }
    if (null == t) completedPromise.trySuccess(()) else completedPromise.tryFailure(t)
  }
  
  override protected def finalize(): Unit = close()
}
