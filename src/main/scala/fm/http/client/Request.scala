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

import fm.http._
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.handler.codec.http.{DefaultFullHttpRequest, DefaultHttpRequest, FullHttpRequest, HttpMessage, HttpMethod, HttpRequest, HttpVersion}
import java.io.File

object Request {
  def Get(uri: String, headers: Headers): FullRequest = FullRequest(HttpMethod.GET, uri, headers)
  
  def Head(uri: String, headers: Headers): FullRequest = FullRequest(HttpMethod.HEAD, uri, headers)
  
  def Post(uri: String, headers: Headers): FullRequest = FullRequest(HttpMethod.POST, uri, headers)
  def Post(uri: String, headers: Headers, buf: ByteBuf): FullRequest = FullRequest(HttpMethod.POST, uri, headers, buf)
  def Post(uri: String, headers: Headers, head: LinkedHttpContent): AsyncRequest = AsyncRequest(HttpMethod.POST, uri, headers, head)
  def Post(uri: String, headers: Headers, file: File): FileRequest = FileRequest(HttpMethod.POST, uri, headers, file)
}

sealed trait Request {
  def method: HttpMethod
  def headers: Headers
  
  /** The complete URL that we are requesting (e.g. http://frugalmechanic.com/foo/bar?param=value) */
  def url: String
  
  /** Make a copy of this Request replacing this.headers with newHeaders */
  def withHeaders(newHeaders: Headers): Request
  
  protected def initHeaders[T <: HttpMessage](msg: T): T = {
    msg.headers().add(headers.nettyHeaders)
    msg
  }
  
  override def toString: String = {
    s"${method.name} ${url}\n\n$headers"
  }
}

/**
 * This represents a full HTTP Request (both headers and complete body)
 */
final case class FullRequest(method: HttpMethod, url: String, headers: Headers = Headers.empty, buf: ByteBuf = Unpooled.EMPTY_BUFFER) extends Request with FullMessage {
  def toFullHttpRequest(version: HttpVersion, uri: String): FullHttpRequest = {
    initHeaders(new DefaultFullHttpRequest(version, method, uri, buf))
  }
  
  def withHeaders(newHeaders: Headers): FullRequest = copy(headers = newHeaders)
}

/**
 * This represents a chunked HTTP Request with headers and the first chunk along with a pointer to the next chunk
 */
final case class AsyncRequest(method: HttpMethod, url: String, headers: Headers, head: LinkedHttpContent) extends Request with AsyncMessage {
  def toHttpRequest(version: HttpVersion, uri: String): HttpRequest = {
    initHeaders(new DefaultHttpRequest(version, method, uri))
  }
  
  def withHeaders(newHeaders: Headers): AsyncRequest = copy(headers = newHeaders)
}

/**
 * This represents a File that we want to send as the request body
 */
final case class FileRequest(method: HttpMethod, url: String, headers: Headers, file: File) extends Request with FileMessage {
  def toHttpRequest(version: HttpVersion, uri: String): HttpRequest = {
    initHeaders(new DefaultHttpRequest(version, method, uri))
  }
  
  def withHeaders(newHeaders: Headers): FileRequest = copy(headers = newHeaders)
}