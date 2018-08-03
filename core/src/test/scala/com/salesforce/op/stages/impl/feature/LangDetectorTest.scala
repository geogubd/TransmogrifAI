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

package com.salesforce.op.stages.impl.feature

import com.salesforce.op.features.types._
import com.salesforce.op.test.{OpTransformerSpec, TestFeatureBuilder}
import com.salesforce.op.utils.spark.RichDataset._
import com.salesforce.op.utils.text.Language
import org.apache.spark.ml.Transformer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class LangDetectorTest extends OpTransformerSpec[RealMap, LangDetector[Text]] {

  // scalastyle:off
  val (inputData, f1, f2, f3) = TestFeatureBuilder(
    Seq(
      (
        "I've got a lovely bunch of coconuts".toText,
        "文化庁によりますと、世界文化遺産への登録を目指している、福岡県の「宗像・沖ノ島と関連遺産群」について、ユネスコの諮問機関は、８つの構成資産のうち、沖ノ島など４つについて、「世界遺産に登録することがふさわしい」とする勧告をまとめました。".toText,
        "Première détection d’une atmosphère autour d’une exoplanète de la taille de la Terre".toText
      ),
      (
        "There they are, all standing in a row".toText,
        "地磁気発生の謎に迫る地球内部の環境、再現実験".toText,
        "Les deux commissions, créées respectivement en juin 2016 et janvier 2017".toText
      ),
      (
        "Big ones, small ones, some as big as your head".toText,
        "大学レスリング界で「黒船」と呼ばれたカザフスタン出身の大型レスラーが、日本の男子グレコローマンスタイルの重量級強化のために一役買っている。山梨学院大をこの春卒業したオレッグ・ボルチン（２４）。４月から新日本プロレスの親会社ブシロードに就職。自身も日本を拠点に、アマチュアレスリングで２０２０年東京五輪を目指す。".toText,
        "Il publie sa théorie de la relativité restreinte en 1905".toText
      )
    )
  )
  // scalastyle:on
  val transformer = new LangDetector[Text]().setInput(f1)

  private val langMap = f1.detectLanguages()

  // English result
  val expectedResult: Seq[RealMap] = Seq(
    Map("en" -> 0.9999984360934321),
    Map("en" -> 0.9999900853228016),
    Map("en" -> 0.9999900116744931)
  ).map(_.toRealMap)

  it should "return empty RealMap when input text is empty" in {
    transformer.transformFn(Text.empty) shouldBe RealMap.empty
  }

  it should "detect Japanese language" in {
    assertDetectionResults(
      results = transformer.setInput(f2).transform(inputData).collect(transformer.getOutput()),
      expectedLanguage = Language.Japanese
    )
  }

  it should "detect French language" in {
    assertDetectionResults(
      results = transformer.setInput(f3).transform(inputData).collect(transformer.getOutput()),
      expectedLanguage = Language.French
    )
  }

  it should "has a working shortcut" in {
    val tokenized = f1.detectLanguages()

    assertDetectionResults(
      results = tokenized.originStage.asInstanceOf[Transformer].transform(inputData).collect(tokenized),
      expectedLanguage = Language.English
    )
  }

  private def assertDetectionResults
  (
    results: Array[RealMap],
    expectedLanguage: Language,
    confidence: Double = 0.99
  ): Unit =
    results.foreach(res => {
      res.value.size shouldBe 1
      res.value.contains(expectedLanguage.entryName) shouldBe true
      res.value(expectedLanguage.entryName) should be >= confidence
    })

}
