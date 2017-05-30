/**
  * Copyright 2015 Otto (GmbH & Co KG)
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package org.schedoscope.test

import java.util.logging.{Level, LogManager, Logger}

import org.scalatest._
import org.schedoscope.dsl.{Field, FieldLike}
import org.schedoscope.test.resources.{LocalTestResources, TestResources}
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.collection.mutable.ListBuffer

/**
  * Default trait for writing schedoscope tests
  */
trait SchedoscopeSpec extends FlatSpec with SchedoscopeSuite with Matchers

/**
  * This trait enables the re-usage of the ViewUnderTest for several test-cases.
  */
trait SchedoscopeSuite
  extends BeforeAndAfterAll
    with BeforeAndAfterEach {
  this: Suite =>

  Class.forName("parquet.Log")

  private def silenceLogging {
    val parquetLogger = LogManager.getLogManager.getLogger("parquet")
    if (parquetLogger != null) {
      parquetLogger.setLevel(Level.OFF)
      parquetLogger.getHandlers.foreach(parquetLogger.removeHandler(_))
    }
   
    val shadedParquetLogger = LogManager.getLogManager.getLogger("shaded.parquet")
    if (shadedParquetLogger != null) {
      shadedParquetLogger.setLevel(Level.OFF)
      shadedParquetLogger.getHandlers.foreach(shadedParquetLogger.removeHandler(_))
    }
  }

  val views = ListBuffer.empty[test]

  override protected def beforeAll(configMap: org.scalatest.ConfigMap) = {
    silenceLogging
    views.foreach {
      v => v.`then`()
    }
    super.beforeAll(configMap)
  }

  /**
    * Register the view which will be loaded upon the start of the test
    * suite. This view can then be queried inside the individual tests.
    * The usage of this method is optional.
    *
    * @param view view under test
    * @tparam T generic type of the view
    * @return
    */
  def putViewUnderTest[T <: test](view: T): T = {
    views += view
    view
  }
}

/**
  * This test suite lets you predefine fixtures that can be reused in
  * several test cases. Leading to faster runtime and improved readability.
  * The suite can be mixed into the [[SchedoscopeSpec]] trait.
  */
trait ReusableHiveSchema
  extends BeforeAndAfterEach
    with AccessRowData {
  this: Suite =>

  var resources: TestResources = new LocalTestResources
  val rowData = new ListBuffer[Map[String, Any]]()

  override protected def afterEach() {
    rowIdx = 0
    rowData.clear()
    super.afterEach()
  }

  /**
    * Replace the default [[TestResources]].
    *
    * @param resources new resources
    */
  def setTestResources(resources: TestResources) {
    this.resources = resources
  }

  /**
    * Call this to initiate the loading of a view.
    * Before this call you should define the input.
    * After this call you can add the assertions.
    *
    * @param view                            to load
    * @param sortedBy                        sorting of the results
    * @param disableDependencyCheck          disable the check for based on
    * @param disableTransformationValidation disable validation of transformations
    * @param disableLineageValidation        disable validation of data lineage
    */
  def then(view: LoadableView, sortedBy: FieldLike[_] = null,
           disableDependencyCheck: Boolean = false,
           disableTransformationValidation: Boolean = false,
           disableLineageValidation: Boolean = true) {

    view.resources = resources
    view.inputFixtures.foreach { v =>
      v.resources = resources
    }

    TestUtils.loadView(view, null,
      disableDependencyCheck,
      disableTransformationValidation,
      disableLineageValidation)
    view.localResources.clear()

    rowData.appendAll(view.rowData)

    view.inputFixtures.filter(!_.isStatic).foreach { v =>
      v.rowData.clear()
    }

  }

  def v[T](f: Field[T], v: T) = (f, v)

}
