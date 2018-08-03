/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op

import java.io.File

import com.salesforce.op.utils.json.{JsonLike, JsonUtils}

import scala.concurrent.duration.Duration
import scala.util.Try


/**
 * OpParams for passing in command line information
 *
 * @param stageParams                 a map of parameters to inject into stages.
 *                                    Format is Map(StageSimpleName -> Map(ParameterName -> Value)).
 *                                    Allows changing parameters away from defaults (and only defaults)
 *                                    at the level of stage type
 *                                    (all stages of the same type will get the same setting.
 *                                    Note: Will NOT override parameter values that have been
 *                                    set previously in code OR with a previous set of parameters.
 * @param readerParams                a map of parameters to inject into readers.
 *                                    Format is Map(ReaderType -> Map(ParameterName -> Value)).
 *                                    In order to use these parameters the read method
 *                                    in the reader must be overwritten to
 *                                    specifically take and use these parameters.
 * @param modelLocation               location to save model to or read model from
 * @param writeLocation               location to write out any data generated by flow
 * @param metricsLocation             location to write out any metrics generated by flow
 * @param metricsCompress             should compress metrics file
 * @param metricsCodec                compress with the supplied codec
 * @param batchDurationSecs           the time interval at which streaming data will be divided into batches
 * @param awaitTerminationTimeoutSecs the time to await until streaming context termination
 * @param customTagName               tag name printed on log lines
 *                                    (e.g., by [[com.salesforce.op.utils.spark.OpSparkListener]])
 * @param customTagValue              the value for the tag printed on log lines
 *                                    (e.g., by [[com.salesforce.op.utils.spark.OpSparkListener]])
 * @param logStageMetrics             if [[com.salesforce.op.utils.spark.OpSparkListener]]
 *                                    should log metrics for every stage.
 *                                    Note: can increase logging significantly if app has too many stages.
 *
 * @param collectStageMetrics         if [[com.salesforce.op.utils.spark.OpSparkListener]]
 *                                    should collect metrics for every stage.
 *                                    Note: can increase memory usage on the driver if app has too many stages.
 * @param customParams                any custom parameters
 * @param alternateReaderParams       a map of parameters to inject into readers - other than the main reader
 *                                    being called in the workflow run
 *                                    Format is Map(ReaderType -> Map(ParameterName -> Value)).
 *                                    In order to use these parameters the read method
 *                                    in the reader must be overwritten to
 *                                    specifically take and use these parameters.
 */
final class OpParams
(
  val stageParams: Map[String, Map[String, Any]],
  val readerParams: Map[String, ReaderParams],
  val modelLocation: Option[String],
  val writeLocation: Option[String],
  val metricsLocation: Option[String],
  val metricsCompress: Option[Boolean],
  val metricsCodec: Option[String],
  val batchDurationSecs: Option[Int],
  val awaitTerminationTimeoutSecs: Option[Int],
  val customTagName: Option[String],
  val customTagValue: Option[String],
  val logStageMetrics: Option[Boolean],
  val collectStageMetrics: Option[Boolean],
  val customParams: Map[String, Any],
  val alternateReaderParams: Map[String, ReaderParams]
) extends JsonLike {

  // fix for jackson putting in nulls
  def this() = this(Map.empty, Map.empty, None, None, None, None, None, None, None, None,
    None, None, None, Map.empty, Map.empty)

  /**
   * Copy op params with new specified values
   *
   * @param readLocations               read locations
   * @param writeLocation               write locations
   * @param modelLocation               model location
   * @param metricsLocation             metrics location
   * @param batchDurationSecs           the time interval at which streaming data will be divided into batches
   * @param awaitTerminationTimeoutSecs the time to await until streaming context termination
   * @param alternateReadLocations      read locations for readers other than the main reader in the workflow
   * @return a copy of params with new values
   */
  def withValues(
    readLocations: Map[String, String] = Map.empty,
    writeLocation: Option[String] = None,
    modelLocation: Option[String] = None,
    metricsLocation: Option[String] = None,
    batchDurationSecs: Option[Int] = None,
    awaitTerminationTimeoutSecs: Option[Int] = None,
    alternateReadLocations: Map[String, String] = Map.empty
  ): OpParams = {

    def readParamsUpdate(
      rParam: Map[String, ReaderParams],
      locations: Map[String, String]
    ): Map[String, ReaderParams] = {
      // some version issue on the cluster (cannot produce locally) introduces nulls
      val oldParams = if (rParam != null) rParam else Map.empty[String, ReaderParams]
      val existingReadLocations = oldParams.collect { case (k, r) if r.path.isDefined => k -> r.path.get }
      (existingReadLocations ++ locations).map { case (k, v) =>
        if (oldParams.contains(k)) k -> oldParams(k).withValues(path = v)
        else k -> new ReaderParams(path = Option(v), partitions = None, customParams = Map.empty)
      }
    }

    val newReaderParams = readParamsUpdate(readerParams, readLocations)
    val newAltReaderParams = readParamsUpdate(alternateReaderParams, alternateReadLocations)
    new OpParams(
      stageParams = stageParams,
      readerParams = newReaderParams,
      modelLocation = if (modelLocation.nonEmpty) modelLocation else this.modelLocation,
      writeLocation = if (writeLocation.nonEmpty) writeLocation else this.writeLocation,
      metricsLocation = if (metricsLocation.nonEmpty) metricsLocation else this.metricsLocation,
      batchDurationSecs = if (batchDurationSecs.nonEmpty) batchDurationSecs else this.batchDurationSecs,
      awaitTerminationTimeoutSecs =
        if (awaitTerminationTimeoutSecs.nonEmpty) awaitTerminationTimeoutSecs else this.awaitTerminationTimeoutSecs,
      metricsCompress = metricsCompress,
      metricsCodec = metricsCodec,
      customTagName = customTagName,
      customTagValue = customTagValue,
      logStageMetrics = logStageMetrics,
      collectStageMetrics = collectStageMetrics,
      customParams = customParams,
      alternateReaderParams = newAltReaderParams
    )
  }

  /**
   * Switch the reader params with the alternate reader params and return a new params instance
   * this is necessary because the readers will always try to use readerParams so if you want to
   * use the alternate reader params they need to be moved to readerParams
   * @return a new params instance
   */
  def switchReaderParams(): OpParams = new OpParams(
    stageParams = stageParams,
    readerParams = alternateReaderParams,
    modelLocation = modelLocation,
    writeLocation = writeLocation,
    metricsLocation = metricsLocation,
    metricsCompress = metricsCompress,
    metricsCodec = metricsCodec,
    batchDurationSecs = batchDurationSecs,
    awaitTerminationTimeoutSecs = awaitTerminationTimeoutSecs,
    customTagName = customTagName,
    customTagValue = customTagValue,
    logStageMetrics = logStageMetrics,
    collectStageMetrics = collectStageMetrics,
    customParams = customParams,
    alternateReaderParams = readerParams
  )
}

/**
 * Reader params
 *
 * @param path         read path
 * @param partitions   if specified, will repartition
 * @param customParams any custom parameters
 */
final class ReaderParams
(
  val path: Option[String],
  val partitions: Option[Int],
  val customParams: Map[String, Any]
) extends JsonLike {

  def this() = this(None, None, Map.empty) // fix for jackson putting in nulls

  /**
   * Copy reader params with new values
   *
   * @param path path
   * @return a copy of params with new values
   */
  def withValues(path: String): ReaderParams =
    new ReaderParams(
      path = Option(path),
      partitions = partitions,
      customParams = customParams
    )
}

/**
 * [[OpParams]] factory
 */
object OpParams {

  /**
   * Creates an instance of [[OpParams]]
   */
  def apply( // scalastyle:off parameter.number
    stageParams: Map[String, Map[String, Any]] = Map.empty,
    readerParams: Map[String, ReaderParams] = Map.empty,
    modelLocation: Option[String] = None,
    writeLocation: Option[String] = None,
    metricsLocation: Option[String] = None,
    metricsCompress: Option[Boolean] = None,
    metricsCodec: Option[String] = None,
    batchDurationSecs: Option[Int] = None,
    awaitTerminationTimeoutSecs: Option[Int] = None,
    customTagName: Option[String] = None,
    customTagValue: Option[String] = None,
    logStageMetrics: Option[Boolean] = None,
    collectStageMetrics: Option[Boolean] = None,
    customParams: Map[String, Any] = Map.empty,
    scoringReaderParams: Map[String, ReaderParams] = Map.empty
  ): OpParams = new OpParams(
    stageParams = stageParams,
    readerParams = readerParams,
    modelLocation = modelLocation,
    writeLocation = writeLocation,
    metricsLocation = metricsLocation,
    metricsCompress = metricsCompress,
    metricsCodec = metricsCodec,
    batchDurationSecs = batchDurationSecs,
    awaitTerminationTimeoutSecs = awaitTerminationTimeoutSecs,
    customTagName = customTagName,
    customTagValue = customTagValue,
    logStageMetrics = logStageMetrics,
    collectStageMetrics = collectStageMetrics,
    customParams = customParams,
    alternateReaderParams = scoringReaderParams
  )

  /**
   * Read OpParams params from a json or yaml file
   *
   * @param paramsFile json or yaml file to read params from
   * @return Try[OpWorkflowParams]
   */
  def fromFile(paramsFile: File): Try[OpParams] = JsonUtils.fromFile[OpParams](paramsFile)

  /**
   * Read OpParams params from a json or yaml string
   *
   * @param paramsStr params string as a json or yaml
   * @return Try[OpWorkflowParams]
   */
  def fromString(paramsStr: String): Try[OpParams] = JsonUtils.fromString[OpParams](paramsStr)

  /**
   * Write params instance to yaml string
   *
   * @param params instance
   * @return yaml string of the instance
   */
  def toYamlString(params: OpParams): String = JsonUtils.toYamlString(params)

}
