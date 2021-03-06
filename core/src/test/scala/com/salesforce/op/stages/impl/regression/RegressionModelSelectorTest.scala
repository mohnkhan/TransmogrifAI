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

package com.salesforce.op.stages.impl.regression

import com.salesforce.op.evaluators._
import com.salesforce.op.features.types._
import com.salesforce.op.features.{Feature, FeatureBuilder}
import com.salesforce.op.stages.impl.CompareParamGrid
import com.salesforce.op.stages.impl.regression.RegressionModelsToTry._
import com.salesforce.op.stages.impl.regression.RegressorType._
import com.salesforce.op.stages.impl.selector.{DefaultSelectorParams, ModelSelectorBaseNames, ModelSelectorSummary}
import com.salesforce.op.stages.impl.tuning.BestEstimator
import com.salesforce.op.test.TestSparkContext
import com.salesforce.op.utils.spark.RichDataset._
import com.salesforce.op.utils.spark.RichMetadata._
import org.apache.spark.ml.linalg.{Vector, Vectors}
import org.apache.spark.ml.regression.{DecisionTreeRegressor, GBTRegressor, RandomForestRegressor}
import org.apache.spark.ml.tuning.ParamGridBuilder
import org.apache.spark.sql.Encoders
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class RegressionModelSelectorTest extends FlatSpec with TestSparkContext with CompareParamGrid {
  val seed = 1234L
  val stageNames = "label_prediction"

  import spark.implicits._

  val rawData: Seq[(Double, Vector)] = List.range(0, 100, 1).map(i =>
    (i.toDouble, Vectors.dense(2 * i, 4 * i)))

  val data = sc.parallelize(rawData).toDF("label", "features")

  val (label, Array(features: Feature[OPVector]@unchecked)) = FeatureBuilder.fromDataFrame[RealNN](
    data, response = "label", nonNullable = Set("features")
  )
  val modelSelector = RegressionModelSelector().setInput(label.asInstanceOf[Feature[RealNN]], features)

  Spec[RegressionModelSelector] should "be properly set" in {
    val inputNames = modelSelector.getInputFeatures().map(_.name)
    inputNames should have length 2
    inputNames shouldBe Array(label.name, features.name)
    modelSelector.getOutput().name shouldBe modelSelector.getOutputFeatureName
    the[IllegalArgumentException] thrownBy {
      modelSelector.setInput(label, features.copy(isResponse = true))
    } should have message "The feature vector should not contain any response features."
  }

  it should "properly select models to try" in {
    modelSelector.setModelsToTry(LinearRegression, RandomForestRegression)

    modelSelector.get(modelSelector.useLR).get shouldBe true
    modelSelector.get(modelSelector.useRF).get shouldBe true
    modelSelector.get(modelSelector.useDT).get shouldBe false
    modelSelector.get(modelSelector.useGBT).get shouldBe false
  }

  it should "set the Linear Regression Params properly" in {
    modelSelector
      .setLinearRegressionMaxIter(10)
      .setLinearRegressionElasticNetParam(0.1)
      .setLinearRegressionFitIntercept(true, false)
      .setLinearRegressionRegParam(0.1, 0.01)
      .setLinearRegressionStandardization(true)
      .setLinearRegressionTol(0.005, 0.0002)

    val lrGrid = new ParamGridBuilder().addGrid(modelSelector.sparkLR.fitIntercept, Array(true, false))
      .addGrid(modelSelector.sparkLR.regParam, Array(0.1, 0.01))
      .addGrid(modelSelector.sparkLR.tol, Array(0.005, 0.0002))
      .addGrid(modelSelector.sparkLR.maxIter, Array(10))
      .addGrid(modelSelector.sparkLR.elasticNetParam, Array(0.1))
      .addGrid(modelSelector.sparkLR.standardization, Array(true))
      .addGrid(modelSelector.sparkLR.solver, Array(DefaultSelectorParams.RegSolver.sparkName))
      .build

    gridCompare(modelSelector.lRGrid.build(), lrGrid)
  }

  it should "set the Random Forest Params properly" in {
    modelSelector.setRandomForestMaxBins(34)
      .setRandomForestMaxDepth(7, 8)
      .setRandomForestMinInfoGain(0.1)
      .setRandomForestMinInstancesPerNode(2, 3, 4)
      .setRandomForestSeed(34L)
      .setRandomForestSubsamplingRate(0.4, 0.8)
      .setRandomForestNumTrees(10)

    val sparkRandomForest = modelSelector.sparkRF.asInstanceOf[RandomForestRegressor]

    val rfGrid =
      new ParamGridBuilder().addGrid(sparkRandomForest.maxDepth, Array(7, 8))
        .addGrid(sparkRandomForest.minInstancesPerNode, Array(2, 3, 4))
        .addGrid(sparkRandomForest.subsamplingRate, Array(0.4, 0.8))
        .addGrid(sparkRandomForest.maxBins, Array(34))
        .addGrid(sparkRandomForest.minInfoGain, Array(0.1))
        .addGrid(sparkRandomForest.seed, Array(34L))
        .addGrid(sparkRandomForest.numTrees, Array(10))
        .addGrid(sparkRandomForest.impurity, Array(DefaultSelectorParams.ImpurityReg.sparkName))
        .build

    gridCompare(modelSelector.rFGrid.build(), rfGrid)
  }

  it should "set the Decision Tree Params properly" in {
    modelSelector.setDecisionTreeMaxBins(34, 44)
      .setDecisionTreeMaxDepth(10)
      .setDecisionTreeMinInfoGain(0.2, 0.5)
      .setDecisionTreeMinInstancesPerNode(5)
      .setDecisionTreeSeed(34L, 56L)

    val sparkDecisionTree = modelSelector.sparkDT.asInstanceOf[DecisionTreeRegressor]

    val dtGrid =
      new ParamGridBuilder()
        .addGrid(sparkDecisionTree.maxBins, Array(34, 44))
        .addGrid(sparkDecisionTree.minInfoGain, Array(0.2, 0.5))
        .addGrid(sparkDecisionTree.seed, Array(34L, 56L))
        .addGrid(sparkDecisionTree.maxDepth, Array(10))
        .addGrid(sparkDecisionTree.minInstancesPerNode, Array(5))
        .addGrid(sparkDecisionTree.impurity, Array(DefaultSelectorParams.ImpurityReg.sparkName))
        .build

    gridCompare(modelSelector.dTGrid.build(), dtGrid)
  }

  it should "set the Gradient Boosted Tree Params properly" in {
    modelSelector.setGradientBoostedTreeMaxBins(34, 44)
      .setGradientBoostedTreeMinInfoGain(0.2, 0.5)
      .setGradientBoostedTreeSeed(34L, 56L)
      .setGradientBoostedTreeLossType(LossType.Squared, LossType.Absolute)
      .setGradientBoostedTreeMaxDepth(10)
      .setGradientBoostedTreeMinInstancesPerNode(5)
      .setGradientBoostedTreeStepSize(0.5)

    val sparkGBTTree = modelSelector.sparkGBT.asInstanceOf[GBTRegressor]

    val dtGrid =
      new ParamGridBuilder()
        .addGrid(sparkGBTTree.maxBins, Array(34, 44))
        .addGrid(sparkGBTTree.minInfoGain, Array(0.2, 0.5))
        .addGrid(sparkGBTTree.seed, Array(34L, 56L))
        .addGrid(sparkGBTTree.lossType, Array(LossType.Squared, LossType.Absolute).map(_.sparkName))
        .addGrid(sparkGBTTree.maxDepth, Array(10))
        .addGrid(sparkGBTTree.minInstancesPerNode, Array(5))
        .addGrid(sparkGBTTree.impurity, Array(DefaultSelectorParams.ImpurityReg.sparkName))
        .addGrid(sparkGBTTree.stepSize, Array(0.5))
        .addGrid(sparkGBTTree.maxIter, Array(DefaultSelectorParams.MaxIterTree))
        .addGrid(sparkGBTTree.subsamplingRate, Array(DefaultSelectorParams.SubsampleRate))
        .build

    gridCompare(modelSelector.gBTGrid.build(), dtGrid)
  }

  it should "set the data splitting params correctly" in {
    modelSelector.splitter.get.setReserveTestFraction(0.1).setSeed(11L)
    modelSelector.splitter.get.getSeed shouldBe 11L
    modelSelector.splitter.get.getReserveTestFraction shouldBe 0.1
  }

  it should "split into training and test" in {

    implicit val vectorEncoder: org.apache.spark.sql.Encoder[Vector] = ExpressionEncoder()
    implicit val e1 = Encoders.tuple(Encoders.scalaDouble, vectorEncoder)

    val (train, test) = modelSelector.setInput(label, features)
      .splitter.get.setReserveTestFraction(0.2).split(data.as[(Double, Vector)])

    val trainCount = train.count()
    val testCount = test.count()
    val totalCount = rawData.length

    assert(math.abs(testCount - 0.2 * totalCount) <= 3)
    assert(math.abs(trainCount - 0.8 * totalCount) <= 3)

    trainCount + testCount shouldBe totalCount
  }

  it should "fit and predict" in {
    val testEstimator =
      RegressionModelSelector
        .withCrossValidation(numFolds = 4, validationMetric = Evaluators.Regression.mse(), seed = 10L)
        .setModelsToTry(LinearRegression, RandomForestRegression)
        .setLinearRegressionElasticNetParam(0, 0.5, 1)
        .setLinearRegressionMaxIter(10, 100)
        .setLinearRegressionRegParam(0)
        .setLinearRegressionSolver(Solver.LBFGS)
        .setRandomForestMaxDepth(2, 10)
        .setRandomForestNumTrees(10)
        .setRandomForestMinInfoGain(0)
        .setRandomForestMinInstancesPerNode(1)
        .setRandomForestMaxDepth(5)
        .setInput(label, features)

    val model = testEstimator.fit(data)
    model.evaluateModel(data)
    val pred = model.getOutput()

    // evaluation metrics from train set should be in metadata
    val metaData = ModelSelectorSummary.fromMetadata(model.getMetadata().getSummaryMetadata())
    RegressionEvalMetrics.values.foreach(metric =>
      assert(metaData.trainEvaluation.toJson(false).contains(s"${metric.entryName}"),
        s"Metric ${metric.entryName} is not present in metadata: " + metaData)
    )

    // evaluation metrics from train set should be in metadata
    val metaDataHoldOut = ModelSelectorSummary.fromMetadata(model.getMetadata().getSummaryMetadata()).holdoutEvaluation
    RegressionEvalMetrics.values.foreach(metric =>
      assert(metaDataHoldOut.get.toJson(false).contains(s"${metric.entryName}"),
        s"Metric ${metric.entryName} is not present in metadata: " + metaData)
    )

    val transformedData = model.transform(data)
    val justScores = transformedData.collect(pred)

    assertScores(justScores, transformedData.collect(label))
  }

  it should "fit and predict with a train validation split even if there is no split between training and test" in {
    val testEstimator =
      RegressionModelSelector
        .withTrainValidationSplit(
          dataSplitter = None,
          trainRatio = 0.8,
          validationMetric = Evaluators.Regression.r2(),
          seed = 10L
        )
        .setModelsToTry(LinearRegression, RandomForestRegression)
        .setLinearRegressionElasticNetParam(0.5)
        .setLinearRegressionMaxIter(10, 100)
        .setLinearRegressionSolver(Solver.Auto)
        .setLinearRegressionRegParam(0)
        .setRandomForestMaxDepth(2, 10)
        .setRandomForestNumTrees(10)
        .setRandomForestMinInfoGain(0)
        .setRandomForestMinInstancesPerNode(1)
        .setRandomForestMaxDepth(5)
        .setInput(label, features)
    val pred = testEstimator.getOutput()
    val transformedData = testEstimator.fit(data).transform(data)
    val justScores = transformedData.collect(pred)

    assertScores(justScores, transformedData.collect(label))
  }

  it should "fit and predict correctly with MeanAbsoluteError used" in {
    val testEstimator =
      RegressionModelSelector
        .withCrossValidation(numFolds = 4, validationMetric = Evaluators.Regression.mae(), seed = 11L)
        .setModelsToTry(LinearRegression, RandomForestRegression)
        .setLinearRegressionElasticNetParam(0, 0.5, 1)
        .setLinearRegressionSolver(Solver.LBFGS)
        .setLinearRegressionRegParam(0)
        .setRandomForestMaxDepth(2, 10)
        .setRandomForestNumTrees(10)
        .setRandomForestMinInfoGain(0)
        .setRandomForestMinInstancesPerNode(1)
        .setRandomForestMaxDepth(5)
        .setInput(label, features)
    val pred = testEstimator.getOutput()
    val transformedData = testEstimator.fit(data).transform(data)
    val justScores = transformedData.collect(pred)

    assertScores(justScores, transformedData.collect(label))
  }

  it should "fit and predict correctly with a custom metric used" in {

    val medianAbsoluteError = Evaluators.Regression.custom(
      metricName = "median absolute error",
      isLargerBetter = false,
      evaluateFn = ds => {
        val medAE = ds.map { case (lbl, prediction) => math.abs(prediction - lbl) }
        val median = medAE.stat.approxQuantile(medAE.columns.head, Array(0.5), 0.25)
        median.head
      }
    )
    val testEstimator =
      RegressionModelSelector
        .withCrossValidation(numFolds = 4, validationMetric = medianAbsoluteError, seed = 11L)
        .setModelsToTry(LinearRegression, RandomForestRegression)
        .setLinearRegressionElasticNetParam(0, 0.5, 1)
        .setLinearRegressionSolver(Solver.Normal)
        .setLinearRegressionRegParam(0)
        .setRandomForestMaxDepth(2, 10)
        .setRandomForestNumTrees(10)
        .setRandomForestMinInfoGain(0)
        .setRandomForestMinInstancesPerNode(1)
        .setRandomForestMaxDepth(5)
        .setInput(label, features)
    val pred = testEstimator.getOutput()
    val transformedData = testEstimator.fit(data).transform(data)
    val justScores = transformedData.collect(pred)

    assertScores(justScores, transformedData.collect(label))
  }

  it should "fit correctly with a custom metric used as holdOut evaluator" in {

    val medianAbsoluteError = Evaluators.Regression.custom(
      metricName = "median absolute error",
      isLargerBetter = false,
      evaluateFn = ds => {
        val medAE = ds.map { case (lbl, prediction) => math.abs(prediction - lbl) }
        val median = medAE.stat.approxQuantile(medAE.columns.head, Array(0.5), 0.25)
        median.head
      }
    )
    val testEstimator =
      RegressionModelSelector
        .withCrossValidation(numFolds = 4, validationMetric = medianAbsoluteError,
          trainTestEvaluators = Seq(medianAbsoluteError), seed = 11L)
        .setModelsToTry(LinearRegression, RandomForestRegression)
        .setLinearRegressionElasticNetParam(0, 0.5, 1)
        .setLinearRegressionSolver(Solver.Normal)
        .setLinearRegressionRegParam(0)
        .setRandomForestMaxDepth(2, 10)
        .setRandomForestNumTrees(10)
        .setRandomForestMinInfoGain(0)
        .setRandomForestMinInstancesPerNode(1)
        .setRandomForestMaxDepth(5)
        .setInput(label, features)
    val model = testEstimator.fit(data)

    // checking trainingEval & holdOutEval metrics
    model.evaluateModel(data)
    val metaData = ModelSelectorSummary.fromMetadata(model.getMetadata().getSummaryMetadata())
    val trainMetaData = metaData.trainEvaluation
    val holdOutMetaData = metaData.holdoutEvaluation.get

    testEstimator.evaluators.foreach {
      case evaluator: OpRegressionEvaluator => {
        RegressionEvalMetrics.values.foreach(metric =>
          Seq(trainMetaData, holdOutMetaData).foreach(
            metadata => assert(metadata.toJson(false).contains(s"${metric.entryName}"),
              s"Metric ${metric.entryName} is not present in metadata: " + metadata)
          )
        )
      }
      case evaluator: OpRegressionEvaluatorBase[_] => {
        Seq(trainMetaData, holdOutMetaData).foreach(
          metadata =>
            assert(metadata.toJson(false).contains(s"${evaluator.name.humanFriendlyName}"),
              s"Single Metric evaluator ${evaluator.name} is not present in metadata: " + metadata)
        )
      }
    }
  }

  it should "fit and predict a model specified in the var bestEstimator" in {
    val modelSelector: RegressionModelSelector = RegressionModelSelector().setInput(label, features)
    val myParam = true
    val myEstimatorName = "myEstimatorIsAwesome"
    val myEstimator = new GBTRegressor().setCacheNodeIds(myParam)

    val bestEstimator = new BestEstimator[Regressor](myEstimatorName, myEstimator.asInstanceOf[Regressor], Seq.empty)
    modelSelector.bestEstimator = Option(bestEstimator)
    val fitted = modelSelector.fit(data)

    fitted.getSparkMlStage().get.extractParamMap().get(myEstimator.cacheNodeIds).get shouldBe myParam

    val meta = ModelSelectorSummary.fromMetadata(fitted.getMetadata().getSummaryMetadata())
    meta.bestModelName shouldBe myEstimatorName
  }

  private def assertScores(scores: Array[RealNN], labels: Array[RealNN]) = {
    val res = scores.zip(labels).map { case (score: RealNN, label: RealNN) => math.abs(score.v.get - label.v.get) }.sum
    assert(res <= scores.length, "prediction failed")
  }
}
