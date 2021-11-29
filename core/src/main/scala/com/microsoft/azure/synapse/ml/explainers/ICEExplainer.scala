// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.azure.synapse.ml.explainers

import com.microsoft.azure.synapse.ml.core.schema.DatasetExtensions
import org.apache.spark.ml.{ComplexParamsReadable, ComplexParamsWritable, Transformer}
import org.apache.spark.ml.param.{ParamMap, ParamValidators, Params, _}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import org.apache.spark.ml.stat.Summarizer
import com.microsoft.azure.synapse.ml.codegen.Wrappable


trait ICEFeatureParams extends Params with HasNumSamples {

  val averageKind = "average"
  val individualKind = "individual"

  val categoricalFeatures = new TypedArrayParam[ICECategoricalFeature] (
    this,
    "categoricalFeatures",
    "The list of categorical features to explain.",
    _.forall(_.validate)
  )

  def setCategoricalFeatures(values: Seq[ICECategoricalFeature]): this.type = this.set(categoricalFeatures, values)
  def getCategoricalFeatures: Seq[ICECategoricalFeature] = $(categoricalFeatures)

  val numericFeatures = new TypedArrayParam[ICENumericFeature] (
    this,
    "numericFeatures",
    "The list of numeric features to explain.",
    _.forall(_.validate)
  )

  def setNumericFeatures(values: Seq[ICENumericFeature]): this.type = this.set(numericFeatures, values)
  def getNumericFeatures: Seq[ICENumericFeature] = $(numericFeatures)

  val kind = new Param[String] (
    this,
    "kind",
    "Whether to return the partial dependence plot (PDP) averaged across all the samples in the " +
      "dataset or individual feature importance (ICE) per sample. " +
      "Allowed values are \"average\" for PDP and \"individual\" for ICE.",
    ParamValidators.inArray(Array(averageKind, individualKind))
  )

  def getKind: String = $(kind)
  def setKind(value: String): this.type = set(kind, value)

  setDefault(kind -> "individual", numericFeatures -> Seq.empty[ICENumericFeature],
    categoricalFeatures -> Seq.empty[ICECategoricalFeature])
}

/**
  * ICETransformer displays the model dependence on specified features with the given dataframe
  * as background dataset. It supports 2 types of plots: individual - dependence per instance and
  * average - across all the samples in the dataset.
  * Note: This transformer only supports one-way dependence plot.
  */
class ICETransformer(override val uid: String) extends Transformer
  with HasExplainTarget
  with HasModel
  with ICEFeatureParams
  with Wrappable
  with ComplexParamsWritable {

  override protected lazy val pyInternalWrapper = true

  def this() = {
    this(Identifiable.randomUID("ICETransformer"))
  }

  private def calcDependence(df: DataFrame, idCol: String, targetClassesColumn: String,
                             feature: String, values: Array[_], outputColName: String): DataFrame = {

    val dataType = df.schema(feature).dataType
    val explodeFunc = explode(array(values.map(v => lit(v)): _*).cast(ArrayType(dataType)))

    val predicted = getModel.transform(df.withColumn(feature, explodeFunc))
    val targetCol = DatasetExtensions.findUnusedColumnName("target", predicted)
    val dependenceCol = DatasetExtensions.findUnusedColumnName("feature__dependence", predicted)

    val explainTarget = extractTarget(predicted.schema, targetClassesColumn)
    val result = predicted.withColumn(targetCol, explainTarget)

    getKind.toLowerCase match {
      case this.averageKind =>
        // PDP output schema: 1 row * 1 col (pdp for the given feature: feature_value -> explanations)
        result
          .groupBy(feature)
          .agg(Summarizer.mean(col(targetCol)).alias(dependenceCol))
          .agg(
            map_from_arrays(
              collect_list(feature),
              collect_list(dependenceCol)
            ).alias(outputColName)
          )

      case this.individualKind =>
        // ICE output schema: n rows * 2 cols (idCol + ice for the given feature: map(feature_value -> explanations))
        result
          .groupBy(idCol)
          .agg(
            map_from_arrays(
              collect_list(feature),
              collect_list(targetCol)
            ).alias(outputColName)
          )
    }
  }

  def transform(ds: Dataset[_]): DataFrame = {
    transformSchema(ds.schema)
    val df = ds.toDF
    val idCol = DatasetExtensions.findUnusedColumnName("idCol", df)
    val targetClasses = DatasetExtensions.findUnusedColumnName("targetClasses", df)
    val dfWithId = df
      .withColumn(idCol, monotonically_increasing_id())
      .withColumn(targetClasses, this.get(targetClassesCol).map(col).getOrElse(lit(getTargetClasses)))

    // collect feature values for all features from original dataset - dfWithId
    val (categoricalFeatures, numericFeatures) = (this.getCategoricalFeatures, this.getNumericFeatures)

    val sampled: Dataset[Row] = this.get(numSamples).map(dfWithId.orderBy(rand()).limit).getOrElse(dfWithId).cache

    val calcCategoricalFunc: ICECategoricalFeature => DataFrame = {
      f: ICECategoricalFeature =>
        val values = collectCategoricalValues(dfWithId, f)
        calcDependence(sampled, idCol, targetClasses, f.name, values, f.getOutputColName)
    }
    val calcNumericFunc: ICENumericFeature => DataFrame = {
      f: ICENumericFeature =>
        val values = collectSplits(dfWithId, f)
        calcDependence(sampled, idCol, targetClasses, f.name, values, f.getOutputColName)
    }

    val dependenceDfs = (categoricalFeatures map calcCategoricalFunc) ++ (numericFeatures map calcNumericFunc)

    getKind.toLowerCase match {
      case this.individualKind =>
        dependenceDfs.reduceOption(_.join(_, Seq(idCol), "inner"))
          .map {
            df =>
              (categoricalFeatures ++ numericFeatures).foldLeft(df) {
                case (accDf, feature) => accDf//.withColumnRenamed(feature.name, feature.getOutputColName)
              }
          }
          .map(sampled.join(_, Seq(idCol), "inner").drop(idCol)).get
      case this.averageKind =>
        dependenceDfs.reduce(_ crossJoin _)
    }
  }

  private def collectCategoricalValues[_](df: DataFrame, feature: ICECategoricalFeature): Array[_] = {
    val featureCountCol = DatasetExtensions.findUnusedColumnName("__feature__count__", df)
    val values = df
      .groupBy(col(feature.name))
      .agg(count("*").as(featureCountCol))
      .orderBy(col(featureCountCol).desc)
      .head(feature.getNumTopValue)
      .map(row => row.get(0))
    values
  }

  private def createNSplits(n: Int)(from: Double, to: Double): Seq[Double] = {
    (0 to n) map {
      i => (to - from) / n * i + from
    }
  }

  private def collectSplits(df: DataFrame, numericFeature: ICENumericFeature): Array[Double] = {
    val (feature, nSplits, rangeMin, rangeMax) = (numericFeature.name, numericFeature.getNumSplits,
      numericFeature.rangeMin, numericFeature.rangeMax)
    val featureCol = df.schema(feature)

    val createSplits = createNSplits(nSplits) _

    val values = if (rangeMin.isDefined && rangeMax.isDefined) {
      val (mi, ma) = (rangeMin.get, rangeMax.get)
      // The ranges are defined
      featureCol.dataType match {
        case _@(ByteType | IntegerType | LongType | ShortType) =>
          if (ma.toLong - mi.toLong <= nSplits) {
            // For integral types, no need to create more splits than needed.
            (mi.toLong to ma.toLong) map (_.toDouble)
          } else {
            createSplits(mi, ma)
          }
        case _ =>
          createSplits(mi, ma)
      }
    } else {
      // The ranges need to be calculated from background dataset.
      featureCol.dataType match {
        case _@(ByteType | IntegerType | LongType | ShortType) =>
          val Row(minValue: Long, maxValue: Long) = df
            .agg(min(col(feature)).cast(LongType), max(col(feature)).cast(LongType))
            .head

          val mi = rangeMin.map(_.toLong).getOrElse(minValue)
          val ma = rangeMax.map(_.toLong).getOrElse(maxValue)

          if (ma - mi <= nSplits) {
            // For integral types, no need to create more splits than needed.
            (mi to ma) map (_.toDouble)
          } else {
            createSplits(mi, ma)
          }
        case _ =>
          val Row(minValue: Double, maxValue: Double) = df
            .agg(min(col(feature)).cast(DoubleType), max(col(feature)).cast(DoubleType))
            .head

          val mi = rangeMin.getOrElse(minValue)
          val ma = rangeMax.getOrElse(maxValue)
          createSplits(mi, ma)
      }
    }
    values.toArray
  }

  override def copy(extra: ParamMap): Transformer = this.defaultCopy(extra)

  override def transformSchema(schema: StructType): StructType = {
    // Check the data type for categorical features
    val categoricalFeaturesTypes= getCategoricalFeatures.map(_.name).map(schema(_).dataType)
    val allowedCategoricalTypes = Array(StringType, BooleanType, ByteType, ShortType, IntegerType, LongType)
    require(categoricalFeaturesTypes.forall(allowedCategoricalTypes.contains(_)),
      s"Data type for categorical features must be String, Boolean, Byte, Short, Integer or Long type.")

    // Check the data type for numeric features
    val numericFeaturesTypes= getNumericFeatures.map(_.name).map(schema(_).dataType)
    val allowedNumericTypes = Array(FloatType, DoubleType, DecimalType)
    require(numericFeaturesTypes.forall(allowedNumericTypes.contains(_)),
      s"Data type for numeric features must be Float, Double or Decimal type.")

    // Check if features are specified
    val featureNames = getCategoricalFeatures.map(_.name) ++ getNumericFeatures.map(_.name)
    if (featureNames.isEmpty) {
      throw new Exception("No categorical features or numeric features are set to the explainer. " +
        "Call setCategoricalFeatures or setNumericFeatures to set the features to be explained.")
    }
    // Check for duplicate feature specification
    val duplicateFeatureNames = featureNames.groupBy(identity).mapValues(_.length).filter(_._2 > 1).keys.toArray
    if (duplicateFeatureNames.nonEmpty) {
      throw new Exception(s"Duplicate features specified: ${duplicateFeatureNames.mkString(", ")}")
    }
    this.validateSchema(schema)
    schema
  }
}

object ICETransformer extends ComplexParamsReadable[ICETransformer]