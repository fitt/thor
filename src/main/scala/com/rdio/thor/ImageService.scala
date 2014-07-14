package com.rdio.thor

import java.awt.{Color, Font, GraphicsEnvironment}
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
import com.twitter.util.{Await, Future}
import com.typesafe.config.Config

import org.jboss.netty.handler.codec.http._
import org.jboss.netty.buffer.ChannelBuffers

/** ImageService serves images optionally filtered and blended. */
class ImageService(conf: Config, client: Service[Request, Response]) extends BaseImageService(conf, client) {

  protected def parserFactory(width: Option[Int], height: Option[Int]) = new LayerParser(width, height)

  def getAspectRatio(image: Image): Float = {
    return image.width.toFloat / image.height.toFloat
  }

  def inferDimensions(image: Option[Image], width: Option[Int], height: Option[Int]): Tuple2[Int, Int] = {
    (width, height) match {
      // Both provided
      case (Some(width), Some(height)) => (width, height)

      // Width provided
      case (Some(width), None) => {
        image match {
          case Some(image) => (width, (width.toFloat * getAspectRatio(image)).toInt)
          case None => (width, width)
        }
      }

      // Height provided
      case (None, Some(height)) => {
        image match {
          case Some(image) => ((getAspectRatio(image) * height.toFloat).toInt, height)
          case None => (height, height)
        }
      }

      // None provided
      case (None, None) => {
        image match {
          case Some(image) => (image.width, image.height)
          case None => (200, 200)
        }
      }
    }
  }

  def tryGetImage(pathOrImage: ImageNode, imageMap: Map[String, Image], completedLayers: Array[Image], width: Option[Int], height: Option[Int]): Option[Image] = {
    pathOrImage match {
      case IndexNode(index) if index < completedLayers.length => Some(completedLayers(index))
      case PathNode(path) if imageMap.contains(path) => imageMap.get(path)
      case EmptyNode() => {
        Some {
          val (w, h) = inferDimensions(image, width, height)
          Image.filled(w, h, new Color(0, 0, 0, 0))
        }
      }
      case PreviousNode() if completedLayers.nonEmpty => Some(completedLayers.last)
      case _ => None
    }
  }

  def applyFilter(image: Image, filter: FilterNode, imageMap: Map[String, Image], completedLayers: Array[Image], width: Int, height: Int): Option[Image] = {
    filter match {

      case LinearGradientNode(degrees, colors, stops) =>
        Some(image.filter(LinearGradientFilter(degrees, colors.toArray, stops.toArray)))

      case BlurNode() => Some(image.filter(BlurFilter))

      case BoxBlurNode(hRadius, vRadius) => {
        val originalWidth = image.width
        val originalHeight = image.height
        val downsampleFactor = 4
        val downsampling = 1.0f / downsampleFactor
        val downsampledHRadius: Int = math.round(hRadius * downsampling)
        val downsampledVRadius: Int = math.round(vRadius * downsampling)

        Some {
          image.scale(downsampling).filter(BoxBlurFilter(downsampledHRadius, downsampledVRadius))
            .trim(1, 1, 1, 1) // Remove bleeded edges
            .scaleTo(originalWidth, originalHeight, ScaleMethod.Bicubic) // Scale up a bit to account for trim
        }
      }

      case BoxBlurPercentNode(hPercent, vPercent) => {
        val originalWidth = image.width
        val originalHeight = image.height
        val hRadius = (hPercent * originalWidth.toFloat).toInt
        val vRadius = (vPercent * originalHeight.toFloat).toInt
        val downsampleFactor = 4
        val downsampling = 1.0f / downsampleFactor
        val downsampledHRadius: Int = math.round(hRadius * downsampling)
        val downsampledVRadius: Int = math.round(vRadius * downsampling)

        Some {
          image.scale(downsampling).filter(BoxBlurFilter(downsampledHRadius, downsampledVRadius))
            .trim(1, 1, 1, 1) // Remove bleeded edges
            .scaleTo(originalWidth, originalHeight, ScaleMethod.Bicubic) // Scale up a bit to account for trim
        }
      }

      case TextNode(text, font, color) => {
        font match {
          case FontNode(family, size, style) => {
            val ge: GraphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val fontFamilies: Array[String] = ge.getAvailableFontFamilyNames()
            try {
              val font: Font = if (fontFamilies.contains(family)) {
                new Font(family, style, size)
              } else {
                val resourceStream = getClass.getResourceAsStream(s"/fonts/$family.ttf")
                val font: Font = Font.createFont(Font.TRUETYPE_FONT, resourceStream)
                font.deriveFont(style, size)
              }
              Some(image.filter(TextFilter(text, font, color)))
            } catch {
              case _: Exception => None
            }
          }
        }
      }

      case TextPercentNode(text, font, color) => {
        font match {
          case FontPercentNode(family, percentage, style) => {
            val ge: GraphicsEnvironment = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val fontFamilies: Array[String] = ge.getAvailableFontFamilyNames()
            val size: Int = (percentage * math.max(image.width, image.height).toFloat).toInt
            try {
              val font: Font = if (fontFamilies.contains(family)) {
                new Font(family, style, size)
              } else {
                val resourceStream = getClass.getResourceAsStream(s"/fonts/$family.ttf")
                val font: Font = Font.createFont(Font.TRUETYPE_FONT, resourceStream)
                font.deriveFont(style, size)
              }
              Some(image.filter(TextFilter(text, font, color)))
            } catch {
              case _: Exception => None
            }
          }
        }
      }

      case ColorizeNode(color) => Some(image.filter(ColorizeFilter(color)))

      case ZoomNode(percentage) => {
        val originalWidth = image.width
        val originalHeight = image.height
        Some {
          image.scale(1.0f + percentage, ScaleMethod.Bicubic)
            .resizeTo(originalWidth, originalHeight)
        }
      }

      case ScaleNode(percentage) => Some(image.scale(percentage, ScaleMethod.Bicubic))

      case ScaleToNode(width, height) => Some(image.scaleTo(width, height, ScaleMethod.Bicubic))

      case PadNode(padding) => Some(image.pad(padding, new Color(0, 0, 0, 0)))

      case PadPercentNode(percent) => {
        val padding = (percent * math.max(image.width, image.height).toFloat).toInt
        Some(image.pad(padding, new Color(0, 0, 0, 0)))
      }

      case GridNode(paths) => {
        val images: List[Image] = paths.flatMap {
          path => tryGetImage(path, imageMap, completedLayers, width, height)
        }
        if (images.nonEmpty) {
          if (images.length > 1) {
            Some(image.filter(GridFilter(images.toArray)))
          } else {
            Some(images.head)
          }
        } else {
          log.error(s"Failed to apply grid")
          None
        }
      }

      case RoundCornersNode(radius) => Some(image.filter(RoundCornersFilter(radius)))

      case RoundCornersPercentNode(percent) => {
        val radius = (percent * math.max(image.width, image.height).toFloat).toInt
        Some(image.filter(RoundCornersFilter(radius)))
      }

      case CoverNode(width, height) => Some(image.cover(width, height, ScaleMethod.Bicubic))

      case OverlayNode(overlay) => {
        tryGetImage(overlay, imageMap, completedLayers, width, height) match {
          case Some(overlayImage) => {
            Some(image.filter(OverlayFilter(scaleTo(overlayImage, image.width, image.height))))
          }
          case _ => {
            log.error(s"Failed to apply overlay: $overlay failed to load")
            None
          }
        }
      }

      case MaskNode(overlay, mask) => {
        val overlayOption = tryGetImage(overlay, imageMap, completedLayers, width, height)
        val maskOption = tryGetImage(mask, imageMap, completedLayers, width, height)
        (overlayOption, maskOption) match {
          case (Some(overlayImage), Some(maskImage)) => {
            Some {
              image.filter {
                // We resize the overlay and mask since the filter requires that they be the same size
                MaskFilter(scaleTo(overlayImage, image.width, image.height),
                  scaleTo(maskImage, image.width, image.height))
              }
            }
          }
          case _ => {
            log.error(s"Failed to apply mask: $overlay or $mask failed to load")
            None
          }
        }
      }

      case _: NoopNode => Some(image)
    }
  }

  def applyLayerFilters(imageMap: Map[String, Image], layers: List[LayerNode], width: Option[Int], height: Option[Int]): Option[Image] = {
    // Apply each layer in order
    val completedLayers = ArrayBuffer.empty[Image]
    layers foreach {
      case LayerNode(path: ImageNode, filter: FilterNode) => {
        tryGetImage(path, imageMap, completedLayers.toArray, width, height) match {
          case Some(baseImage) => {
            applyFilter(baseImage, filter, imageMap, completedLayers.toArray, width, height) match {
              case Some(filteredImage) => completedLayers += filteredImage
              case None => {
                log.error(s"Failed to apply layer filter: $path $filter")
                None
              }
            }
          }
          case None => {
            log.error(s"Failed to get layer source: $path")
            None
          }
        }
      }
    }
    completedLayers.lastOption
  }

  def scaleTo(image: Image, width: Int, height: Int): Image = {
    if (image.width == width && image.height == height) {
      image
    } else {
      image.scaleTo(width, height, ScaleMethod.Bicubic)
    }
  }

  def apply(req: Request): Future[Response] = {
    req.params.get("l") match {
      case Some(layers) => {
        val w: Option[Int] = req.params.getInt("w")
        val h: Option[Int] = req.params.getInt("h")

        val width: Option[Int] = w match {
          // Restrict dimensions to the range 1-1200
          case Some(width) => Some(math.min(math.max(width, 1), 1200))
          // Ensure at least one dimension has a value
          case None => if (h.isEmpty) Some(200) else None
        }

        val height: Option[Int] = h match {
          // Restrict dimensions to the range 1-1200
          case Some(height) => Some(math.min(math.max(height, 1), 1200))
          // Ensure at least one dimension has a value
          case None => if (w.isEmpty) Some(200) else None
        }

        // Restrict compression to the range 0-100
        val compression: Int = math.min(math.max(req.params.getIntOrElse("c", 98), 0), 100)

        val format: Format[ImageWriter] = req.params.get("f") match {
          case Some("png") => Format.PNG.asInstanceOf[Format[ImageWriter]]
          case _ => Format.JPEG.asInstanceOf[Format[ImageWriter]]
        }

        val parser = parserFactory(width, height)

        // Parse the layers and attempt to handle each layer
        parser.parseAll(parser.layers, layers) match {
          case parser.Success(layers, _) => {
            // Extract all paths
            val paths = layers flatMap {
              case LayerNode(path, GridNode(paths)) => path +: paths
              case LayerNode(path, MaskNode(overlay, mask)) => path +: List(overlay, mask)
              case LayerNode(path, OverlayNode(overlay)) => path +: List(overlay)
              case LayerNode(path, _: FilterNode) => List(path)
            } flatMap {
              case PathNode(path) => List(path)
              case _ => List()
            }

            // Fetch images by paths
            requestImages(paths.toArray) map {
              potentialImages => {
                // Build a map of paths to images (removing empty paths)
                val imageMap = (paths zip potentialImages).toMap.flatMap {
                  case (path, Some(image)) => List((path, image))
                  case (_, None) => List()
                }

                // Apply any filters to each image and return the final image
                applyLayerFilters(imageMap, layers, width, height) match {
                  case Some(image) => {
                    // Apply final resize and build response
                    buildResponse(req, scaleTo(image, width, height), format, compression)
                  }
                  case None => {
                    Response(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)
                  }
                }
              }
            }
          }
          case unsuccess: parser.NoSuccess => {
            log.error(s"Failed to parse layers: $layers - ${unsuccess.msg}")
            Future.value(Response(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
          }
        }
      }
      case None => {
        log.error(s"No layers found in request: ${req.uri}")
        Future.value(Response(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
      }
    }
  }
}
