package io.kaitai.struct

import scala.Predef._

/** This object was generated by sbt-buildinfo. */
case object BuildInfo {
  /** The value is "kaitai-struct-compiler". */
  val name: String = "kaitai-struct-compiler"
  /** The value is "0.8-SNAPSHOT". */
  val version: String = "0.8-SNAPSHOT"
  /** The value is "2.11.11". */
  val scalaVersion: String = "2.11.11"
  /** The value is "0.13.8". */
  val sbtVersion: String = "0.13.8"
  /** The value is "2017-09-01 17:09:10.560". */
  val builtAtString: String = "2017-09-01 17:09:10.560"
  /** The value is 1504285750560L. */
  val builtAtMillis: scala.Long = 1504285750560L
  override val toString: String = {
    "name: %s, version: %s, scalaVersion: %s, sbtVersion: %s, builtAtString: %s, builtAtMillis: %s" format (
      name, version, scalaVersion, sbtVersion, builtAtString, builtAtMillis
    )
  }
}
