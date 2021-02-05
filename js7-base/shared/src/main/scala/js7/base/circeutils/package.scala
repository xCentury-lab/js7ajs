package js7.base

import _root_.io.circe.{Decoder, Encoder}

/**
  * @author Joacim Zschimmer
  */
package object circeutils {

  type CirceCodec[A] = Encoder[A] with Decoder[A]
  type CirceObjectCodec[A] = Encoder.AsObject[A] with Decoder[A]
}
