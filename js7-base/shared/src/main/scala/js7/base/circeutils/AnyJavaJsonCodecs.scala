package js7.base.circeutils

import io.circe.Json
import js7.base.utils.ScalaUtils.syntax._
import scala.collection.immutable.SeqMap
import scala.jdk.CollectionConverters._

/**
  * @author Joacim Zschimmer
  */
object AnyJavaJsonCodecs {

  //object special {
  //  implicit val JavaMapJsonEncoder: Encoder[java.util.Map[String, Any]] = javaToJson
  //  implicit val JavaMapJsonDecoder: Decoder[java.util.Map[String, Any]] =
  //    c => Right(jsonToJava(c.value).asInstanceOf[java.util.Map[String, Any]])
  //}

  def jsonToJava(json: Json): Any =
    json.asBoolean match {
      case Some(o) => o: Boolean
      case None =>
        json.asNumber match {
          case Some(number) =>
            number.toInt match {
              case Some(int) => int: Int
              case None =>
                number.toLong match {
                  case Some(long) => long: Long
                  case None =>
                    //number.toBigInt match {
                    //  case Some(bigInt) => bigInt.underlying: java.math.BigInteger
                    //  case None =>
                        number.toBigDecimal match {
                          case Some(decimal) => decimal.underlying: java.math.BigDecimal
                          case None => sys.error(s"JSON type ${number.getClass.simpleScalaName} cannot be converted to YAML")
                        }
                    //}
                }
            }
          case None =>
            json.asString match {
              case Some(o) => o: String
              case None if json.isNull => null
              case None =>
                json.asObject match {
                  case Some(o) =>
                    (SeqMap.empty ++
                      o.toVector/*ordered*/
                        .filter(!_._2.isNull)/*remove None*/
                        .map { case (k, v) => k -> jsonToJava(v) }
                    ).asJava
                  case None =>
                    json.asArray match {
                      case Some(o) => (o map jsonToJava).asJava: java.util.List[Any]
                      case None => throw new RuntimeException(s"Unsupported Circe JSON type: ${json.getClass.simpleName}")
                    }
                }
            }
        }
    }
}
