package com.rdio.thor

import java.awt.Color
import java.io.{File, FileInputStream}
import java.net.InetSocketAddress
import java.util.{Calendar, Date}

import scala.collection.mutable.ArrayBuffer

import com.sksamuel.scrimage.{Format, Image, ImageTools, ScaleMethod}
import com.sksamuel.scrimage.io.{ImageWriter, JpegWriter, PngWriter}
import com.sksamuel.scrimage.filter.{ColorizeFilter, BlurFilter}

import com.twitter.conversions.time._
import com.twitter.finagle.Service
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.http.{Http, Status, RichHttp, Request, Response, Message}
import com.twitter.logging.Logger
import com.twitter.util.{Await, Future}
import com.typesafe.config.Config

import org.jboss.netty.handler.codec.http._
import org.jboss.netty.buffer.ChannelBuffers

/** BaseImageService provides common functionality for requesting and serving images. */
object BaseImageService {

  def getContentType[T <: ImageWriter](format: Format[T]): String = format match {
    case Format.JPEG => "image/jpeg"
    case Format.PNG => "image/png"
    case Format.GIF => "image/gif"
  }
}

abstract class BaseImageService(conf: Config, client: Service[Request, Response]) extends Service[Request, Response] {

  protected lazy val mediaHost: String = conf.getString("IMAGESERVER_MEDIA_HOST")
  protected lazy val mediaPort: Int = conf.getInt("IMAGESERVER_MEDIA_PORT")
  protected lazy val cacheDays: Int = conf.getInt("CACHE_DURATION_DAYS")

  protected lazy val log = Logger.get(this.getClass)

  def buildResponse[T <: ImageWriter](req: Request, image: Image, format: Format[T], compression: Int = 98): Response = {
    val bytes = format match {
      case Format.JPEG => image.writer(format).withCompression(compression).withProgressive(true).write()
      case Format.PNG => image.writer(format).withMaxCompression.write()
      case Format.GIF => image.writer(format).withProgressive(true).write()
    }

    val expires: Calendar = Calendar.getInstance()
    expires.add(Calendar.DATE, cacheDays)

    // 1 year = 1 day in seconds x 365 (max age per rfc)
    val cacheSeconds = cacheDays * 24 * 60 * 60

    val res = Response(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
    res.setHeader(HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
    res.setHeader(HttpHeaders.Names.CONTENT_TYPE, BaseImageService.getContentType(format))
    res.setHeader(HttpHeaders.Names.CONTENT_LENGTH, bytes.length.toString)
    res.setHeader(HttpHeaders.Names.CACHE_CONTROL, s"max-age=$cacheSeconds")
    res.setHeader(HttpHeaders.Names.EXPIRES, Message.httpDateFormat(expires.getTime()))
    res.setContent(ChannelBuffers.wrappedBuffer(bytes))
    res
  }

  def requestImage(url: String): Future[Option[Image]] = {
    val req = Request("/" + url)
    req.userAgent = "Thor-Imageserver"
    req.host = s"$mediaHost:$mediaPort"
    req.accept = "image/*"
    client(req) map {
      res => res.status match {
        case Status.Ok => {
          Some(res.withInputStream[Image](inputStream => Image(inputStream)))
        }
        case _ => {
          log.error(s"Could not fetch: $url (${res.status})")
          None
        }
      }
    }
  }

  def requestImages(urls: Array[String]): Future[Seq[Option[Image]]] =
    Future.collect(urls map requestImage)
}
