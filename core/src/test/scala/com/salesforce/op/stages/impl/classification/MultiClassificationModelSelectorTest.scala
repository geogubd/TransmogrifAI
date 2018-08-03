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

package com.salesforce.op.stages.impl.classification

import com.salesforce.op.evaluators._
import com.salesforce.op.features.types._
import com.salesforce.op.features.{Feature, FeatureBuilder}
import com.salesforce.op.stages.impl.CompareParamGrid
import com.salesforce.op.stages.impl.classification.ClassificationModelsToTry._
import com.salesforce.op.stages.impl.classification.FunctionalityForClassificationTests._
import com.salesforce.op.stages.impl.classification.ProbabilisticClassifierType._
import com.salesforce.op.stages.impl.selector.ModelSelectorBaseNames
import com.salesforce.op.stages.impl.tuning._
import com.salesforce.op.stages.sparkwrappers.generic.{SwQuaternaryTransformer, SwTernaryTransformer}
import com.salesforce.op.test.TestSparkContext
import com.salesforce.op.utils.spark.RichDataset._
import com.salesforce.op.utils.spark.RichMetadata._
import org.apache.spark.ml.classification.{DecisionTreeClassifier, LogisticRegressionModel, RandomForestClassifier}
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.ml.tuning.ParamGridBuilder
import org.apache.spark.mllib.random.RandomRDDs._
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.MetadataBuilder
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory


@RunWith(classOf[JUnitRunner])
class MultiClassificationModelSelectorTest extends FlatSpec with TestSparkContext with CompareParamGrid {

  val log = LoggerFactory.getLogger(this.getClass)

  val (seed, label0Count, label1Count, label2Count) = (1234L, 5, 7, 10)

  import spark.implicits._

  // Generate observations of label 1 following a distribution ~ N((-100.0, -100.0, -100.0), I_3)
  val label0Data =
    normalVectorRDD(spark.sparkContext, label0Count, 3, seed = seed)
      .map(v => 0.0 -> Vectors.dense(v.toArray.map(_ - 100.0)))

  // Generate  observations of label 0 following a distribution ~ N((0.0, 0.0, 0.0), I_3)
  val label1Data =
    normalVectorRDD(spark.sparkContext, label1Count, 3, seed = seed)
      .map(v => 1.0 -> Vectors.dense(v.toArray))

  // Generate observations of label 2 following a distribution ~ N((100.0, 100.0, 100.0), I_3)
  val label2Data =
    normalVectorRDD(spark.sparkContext, label2Count, 3, seed = seed)
      .map(v => 2.0 -> Vectors.dense(v.toArray.map(_ + 100.0)))

  val stageNames = Array("label_prediction", "label_rawPrediction", "label_probability")

  val data = label0Data.union(label1Data).union(label2Data).toDF("label", "features")

  val (label, Array(features: Feature[OPVector]@unchecked)) = FeatureBuilder.fromDataFrame[RealNN](
    data, response = "label", nonNullable = Set("features")
  )

  val modelSelector = MultiClassificationModelSelector().setInput(label.asInstanceOf[Feature[RealNN]], features)

  Spec[MultiClassificationModelSelector] should "have properly formed stage1" in {
    assert(modelSelector.stage1.isInstanceOf[Stage1MultiClassificationModelSelector])
    val inputNames = modelSelector.stage1.getInputFeatures().map(_.name)
    inputNames should have length 2
    inputNames shouldBe Array(label.name, features.name)
    modelSelector.stage1.getOutput().name shouldBe modelSelector.stage1.getOutputFeatureName
  }

  it should "have properly formed stage2" in {
    assert(modelSelector.stage2.isInstanceOf[SwTernaryTransformer[_, _, _, _, _]])
    val inputNames = modelSelector.stage2.getInputFeatures().map(_.name)
    inputNames should have length 3
    inputNames shouldBe Array(label.name, features.name, modelSelector.stage1.getOutputFeatureName)
    modelSelector.stage2.getOutput().name shouldBe modelSelector.stage2.getOutputFeatureName
  }

  it should "have properly formed stage3" in {
    assert(modelSelector.stage3.isInstanceOf[SwQuaternaryTransformer[_, _, _, _, _, _]])
    val inputNames = modelSelector.stage3.getInputFeatures().map(_.name)
    inputNames should have length 4
    inputNames shouldBe Array(label.name,
      features.name, modelSelector.stage1.getOutputFeatureName, modelSelector.stage2.getOutputFeatureName
    )
    modelSelector.stage3.getOutput().name shouldBe modelSelector.stage3.getOutputFeatureName
  }

  it should "have proper outputs corresponding to the stages" in {
    val outputs = modelSelector.getOutput()
    outputs._1.name shouldBe modelSelector.stage1.getOutput().name
    outputs._2.name shouldBe modelSelector.stage2.getOutput().name
    outputs._3.name shouldBe modelSelector.stage3.getOutput().name

    // as long as the parent stages are correct, we can also assume
    // that the parent features are correct, since that should
    // be verified in the unit tests for the transformers.
    outputs._1.originStage shouldBe modelSelector.stage1
    outputs._2.originStage shouldBe modelSelector.stage2
    outputs._3.originStage shouldBe modelSelector.stage3
  }

  it should "properly select models to try" in {
    modelSelector.setModelsToTry(LogisticRegression, RandomForest)

    modelSelector.stage1.get(modelSelector.stage1.useLR).get shouldBe true
    modelSelector.stage1.get(modelSelector.stage1.useRF).get shouldBe true
    modelSelector.stage1.get(modelSelector.stage1.useDT).get shouldBe false
    modelSelector.stage1.get(modelSelector.stage1.useNB).get shouldBe false
  }

  it should "set the Logistic Regression Params properly" in {
    modelSelector.setLogisticRegressionElasticNetParam(0.1)
      .setLogisticRegressionFitIntercept(true, false)
      .setLogisticRegressionMaxIter(42)
      .setLogisticRegressionRegParam(0.1, 0.01)
      .setLogisticRegressionStandardization(true)
      .setLogisticRegressionTol(0.005, 0.00002)
      .setLogisticRegressionThreshold(0.8)

    val lrGrid = new ParamGridBuilder().addGrid(modelSelector.stage1.sparkLR.fitIntercept, Array(true, false))
      .addGrid(modelSelector.stage1.sparkLR.regParam, Array(0.1, 0.01))
      .addGrid(modelSelector.stage1.sparkLR.tol, Array(0.005, 0.00002))
      .addGrid(modelSelector.stage1.sparkLR.elasticNetParam, Array(0.1))
      .addGrid(modelSelector.stage1.sparkLR.maxIter, Array(42))
      .addGrid(modelSelector.stage1.sparkLR.standardization, Array(true))
      .addGrid(modelSelector.stage1.sparkLR.threshold, Array(0.8))
      .build

    gridCompare(modelSelector.stage1.lRGrid.build(), lrGrid)
  }

  it should "set the Random Forest Params properly" in {
    modelSelector.setRandomForestImpurity(Impurity.Entropy, Impurity.Gini)
      .setRandomForestMaxBins(34)
      .setRandomForestMaxDepth(7, 8)
      .setRandomForestMinInfoGain(0.1)
      .setRandomForestMinInstancesPerNode(2, 3, 4)
      .setRandomForestSeed(34L)
      .setRandomForestSubsamplingRate(0.4, 0.8)
      .setRandomForestNumTrees(10)

    val sparkRandomForest = modelSelector.stage1.sparkRF.asInstanceOf[RandomForestClassifier]

    val rfGrid =
      new ParamGridBuilder().addGrid(sparkRandomForest.impurity,
        Seq(Impurity.Entropy, Impurity.Gini).map(_.sparkName))
        .addGrid(sparkRandomForest.maxDepth, Array(7, 8))
        .addGrid(sparkRandomForest.minInstancesPerNode, Array(2, 3, 4))
        .addGrid(sparkRandomForest.subsamplingRate, Array(0.4, 0.8))
        .addGrid(sparkRandomForest.maxBins, Array(34))
        .addGrid(sparkRandomForest.minInfoGain, Array(0.1))
        .addGrid(sparkRandomForest.seed, Array(34L))
        .addGrid(sparkRandomForest.numTrees, Array(10))
        .build

    gridCompare(modelSelector.stage1.rFGrid.build(), rfGrid)
  }

  it should "set the Decision Tree Params properly" in {
    modelSelector.setDecisionTreeImpurity(Impurity.Entropy)
      .setDecisionTreeMaxBins(34, 44)
      .setDecisionTreeMaxDepth(10)
      .setDecisionTreeMinInfoGain(0.2, 0.5)
      .setDecisionTreeMinInstancesPerNode(5)
      .setDecisionTreeSeed(34L, 56L)

    val sparkDecisionTree = modelSelector.stage1.sparkDT.asInstanceOf[DecisionTreeClassifier]

    val dtGrid =
      new ParamGridBuilder()
        .addGrid(sparkDecisionTree.maxBins, Array(34, 44))
        .addGrid(sparkDecisionTree.minInfoGain, Array(0.2, 0.5))
        .addGrid(sparkDecisionTree.seed, Array(34L, 56L))
        .addGrid(sparkDecisionTree.impurity, Array(Impurity.Entropy.sparkName))
        .addGrid(sparkDecisionTree.maxDepth, Array(10))
        .addGrid(sparkDecisionTree.minInstancesPerNode, Array(5))
        .build

    gridCompare(modelSelector.stage1.dTGrid.build(), dtGrid)
  }

  it should "set the Naive Bayes Params properly" in {
    modelSelector.setNaiveBayesModelType(ModelType.Multinomial, ModelType.Bernoulli)
    modelSelector.setNaiveBayesSmoothing(1.5)

    val nbGrid =
      new ParamGridBuilder().addGrid(
        modelSelector.stage1.sparkNB.modelType,
        Array(ModelType.Multinomial, ModelType.Bernoulli).map(_.sparkName)
      )
        .addGrid(modelSelector.stage1.sparkNB.smoothing, Array(1.5))
        .build

    gridCompare(modelSelector.stage1.nBGrid.build(), nbGrid)
  }

  it should "set the thresholds correctly" in {
    modelSelector.setModelThresholds(Array(1.0, 2.0))

    modelSelector.stage1.sparkLR.getThresholds shouldBe Array(1.0, 2.0)
    modelSelector.stage1.sparkRF.asInstanceOf[RandomForestClassifier].getThresholds shouldBe Array(1.0, 2.0)
    modelSelector.stage1.sparkDT.asInstanceOf[DecisionTreeClassifier].getThresholds shouldBe Array(1.0, 2.0)
    modelSelector.stage1.sparkNB.getThresholds shouldBe Array(1.0, 2.0)
    modelSelector.stage1.getThresholds shouldBe Array(1.0, 2.0)
  }

  it should "split into training and test" in {
    implicit val vectorEncoder: org.apache.spark.sql.Encoder[Vector] = ExpressionEncoder()
    implicit val e1 = Encoders.tuple(Encoders.scalaDouble, vectorEncoder)

    val testFraction = 0.2

    val (train, test) = DataSplitter(reserveTestFraction = testFraction)
      .split(data.withColumn(ModelSelectorBaseNames.idColName, monotonically_increasing_id())
        .as[(Double, Vector, Double)])


    val trainCount = train.count()
    val testCount = test.count()
    val totalCount = label0Count + label1Count + label2Count

    assert(math.abs(testCount - testFraction * totalCount) <= 20)
    assert(math.abs(trainCount - (1.0 - testFraction) * totalCount) <= 20)

    trainCount + testCount shouldBe totalCount
  }

  it should "fit and predict" in {
    val testEstimator =
      MultiClassificationModelSelector
        .withCrossValidation(
          Option(DataCutter(seed = 42, maxLabelCategories = 1000000, minLabelFraction = 0.0)),
          numFolds = 4,
          validationMetric = Evaluators.MultiClassification.precision(),
          seed = 10L
        )
        .setModelsToTry(LogisticRegression, RandomForest)
        .setLogisticRegressionRegParam(1000, 0.1)
        .setLogisticRegressionMaxIter(0, 10)
        .setRandomForestImpurity(Impurity.Entropy)
        .setRandomForestMaxDepth(0, 10)
        .setRandomForestNumTrees(10)
        .setRandomForestMinInfoGain(0)
        .setRandomForestMinInstancesPerNode(1)
        .setInput(label, features)

    val model = testEstimator.fit(data)

    log.info(model.getMetadata().prettyJson)

    // evaluation metrics from test set should be in metadata
    val metaData = model.getMetadata().getSummaryMetadata().getMetadata(ModelSelectorBaseNames.TrainingEval)
    MultiClassEvalMetrics.values.foreach(metric =>
      assert(metaData.contains(s"(${OpEvaluatorNames.multi})_${metric.entryName}"),
        s"Metric ${metric.entryName} is not present in metadata: " + metaData.json)
    )

    // evaluation metrics from test set should be in metadata after eval run
    model.evaluateModel(data)
    val metaDataHoldOut = model.getMetadata().getSummaryMetadata().getMetadata(ModelSelectorBaseNames.HoldOutEval)
    MultiClassEvalMetrics.values.foreach(metric =>
      assert(metaDataHoldOut.contains(s"(${OpEvaluatorNames.multi})_${metric.entryName}"),
        s"Metric ${metric.entryName} is not present in metadata: " + metaData.json)
    )

    val transformedData = model.transform(data)
    val pred = model.getOutput()._1
    val justScores = transformedData.collect(pred)
    justScores shouldEqual transformedData.collect(label)
  }

  it should "fit and predict with a train validation split, even if there is no split + custom evaluator" in {

    val crossEntropy = Evaluators.MultiClassification.custom(
      metricName = "cross entropy",
      isLargerBetter = false,
      evaluateFn = crossEntropyFun
    )

    val testEstimator =
      MultiClassificationModelSelector
        .withTrainValidationSplit(None, trainRatio = 0.8, validationMetric = crossEntropy, seed = 10L)
        .setModelsToTry(LogisticRegression, RandomForest)
        .setLogisticRegressionRegParam(1000, 0.1)
        .setLogisticRegressionMaxIter(0, 10)
        .setRandomForestImpurity(Impurity.Entropy)
        .setRandomForestMaxDepth(0)
        .setRandomForestNumTrees(10)
        .setRandomForestMinInfoGain(0)
        .setRandomForestMinInstancesPerNode(1)
        .setInput(label, features)

    val model = testEstimator.fit(data)

    val sparkStage = model.stage1.getSparkMlStage().get
    sparkStage.isInstanceOf[LogisticRegressionModel] shouldBe true
    sparkStage.extractParamMap()(sparkStage.getParam("maxIter")) shouldBe 10
    sparkStage.extractParamMap()(sparkStage.getParam("regParam")) shouldBe 0.1

    val transformedData = model.transform(data)
    val pred = testEstimator.getOutput()._1
    val justScores = transformedData.collect(pred)
    val missed = justScores.zip(transformedData.collect(label)).map{ case (p, l) => math.abs(p.v.get - l.v.get) }.sum
    missed < 10 shouldBe true
  }

  it should "fit and predict with a cross validation and compute correct metrics from evaluators" in {

    val crossEntropy = Evaluators.MultiClassification.custom(
      metricName = "cross entropy",
      isLargerBetter = false,
      evaluateFn = crossEntropyFun
    )

    val testEstimator =
      MultiClassificationModelSelector
        .withCrossValidation(
          Option(DataCutter(42, reserveTestFraction = 0.2, maxLabelCategories = 1000000, minLabelFraction = 0.0)),
          numFolds = 4,
          validationMetric = Evaluators.MultiClassification.precision(),
          trainTestEvaluators = Seq(crossEntropy),
          seed = 10L
        )
        .setModelsToTry(DecisionTree, RandomForest)
        .setRandomForestImpurity(Impurity.Gini)
        .setDecisionTreeMaxBins(64, 34)
        .setDecisionTreeMinInfoGain(0)
        .setDecisionTreeMinInstancesPerNode(1)
        .setDecisionTreeMaxDepth(5)
        .setRandomForestMinInfoGain(0)
        .setRandomForestMinInstancesPerNode(1)
        .setRandomForestMaxDepth(5)
        .setInput(label, features)

    val model = testEstimator.fit(data)
    model.evaluateModel(data)

    // checking the holdOut Evaluators
    assert(testEstimator.evaluators.contains(crossEntropy), "Cross entropy evaluator not present in estimator")

    // checking trainingEval & holdOutEval metrics
    val metaData = model.getMetadata().getSummaryMetadata()
    val trainMetaData = metaData.getMetadata(ModelSelectorBaseNames.TrainingEval)
    val holdOutMetaData = metaData.getMetadata(ModelSelectorBaseNames.HoldOutEval)

    testEstimator.evaluators.foreach {
      case evaluator: OpMultiClassificationEvaluator => {
        MultiClassEvalMetrics.values.foreach(metric =>
          Seq(trainMetaData, holdOutMetaData).foreach(
            metadata => assert(metadata.contains(s"(${OpEvaluatorNames.multi})_${metric.entryName}"),
              s"Metric ${metric.entryName} is not present in metadata: " + metadata.json)
          )
        )
      }
      case evaluator: OpMultiClassificationEvaluatorBase[_] => {
        Seq(trainMetaData, holdOutMetaData).foreach(
          metadata => assert(metadata.contains(s"(${evaluator.name})_${evaluator.name}"),
            s"Single Metric evaluator ${evaluator.name} is not present in metadata: " + metadata.json)
        )
      }
    }
  }

  it should "fit and predict a model specified in the var bestEstimator" in {
    val modelSelector: MultiClassificationModelSelector = MultiClassificationModelSelector().setInput(label, features)
    val myParam = "entropy"
    val myMetaName = "myMeta"
    val myMetaValue = "This is a string metadata"
    val myMetadata = new MetadataBuilder().putString(myMetaName, myMetaValue)
    val myEstimatorName = "myEstimatorIsAwesome"
    val myEstimator = new DecisionTreeClassifier().setImpurity(myParam)

    val bestEstimator = new BestEstimator[ProbClassifier](myEstimatorName, myEstimator, myMetadata)
    modelSelector.stage1.bestEstimator = Option(bestEstimator)
    val fitted = modelSelector.fit(data)

    fitted.getParams.get(myEstimator.impurity).get shouldBe myParam

    val meta = fitted.stage1.getMetadata().getMetadata("summary")
    meta.getString(myMetaName) shouldBe myMetaValue
    meta.getString("bestModelName") shouldBe myEstimatorName
  }
}
