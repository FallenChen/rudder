/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.domain.reports.bean

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.DirectiveId
import scala.collection._
import org.joda.time._
import org.joda.time.format._
import com.normation.rudder.domain.Constants
import com.normation.cfclerk.domain.{Cf3PolicyDraftId}
import com.normation.rudder.domain.reports.ReportComponent
import com.normation.rudder.domain.reports.DirectiveExpectedReports
import com.normation.utils.HashcodeCaching
import scala.collection.mutable.Buffer
import ConfigurationExecutionBatch._
import com.normation.rudder.domain.logger.ReportLogger
import com.normation.rudder.domain.policies.Directive
import com.normation.rudder.domain.reports.ReportComponent
import com.normation.inventory.domain.NodeId
import net.liftweb.common.Loggable

/**
 * An execution batch contains the node reports for a given Rule / Directive at a given date
 * An execution Batch is at a given time <- TODO : Is it relevant when we have several node ?
 * @author Nicolas CHARLES
 */
trait ExecutionBatch {
  val ruleId : RuleId
  val serial : Int // the serial of the rule

  val executionTime : DateTime // this is the time of the batch

  val agentExecutionInterval : Int // this is the agent execution interval, in minutes

  // Differents Nodes may have differents version of the same directive.
  // We use this seq to store this information
  // It could even in a not so distant future allow differents node to have differents
  // directive (not simply differents components values, but really different directives)
  val directivesOnNodesExpectedReports : Seq[DirectivesOnNodeExpectedReport]

  val executionReports : Seq[Reports]


  def getNodeStatus() : Seq[NodeStatusReport]

  def getRuleStatus() : Seq[DirectiveRuleStatusReport]

  def getNotApplicableReports() : Seq[Reports] = {
    executionReports.collect { case x : ResultNotApplicableReport => x }
  }

  def getSuccessReports() : Seq[Reports] = {
    executionReports.collect { case x : ResultSuccessReport => x }
  }

  def getRepairedReports() : Seq[Reports] = {
    executionReports.collect { case x : ResultRepairedReport => x }
  }

  def getErrorReports() : Seq[Reports] = {
    executionReports.collect { case x : ResultErrorReport => x }
  }

}

case class DirectivesOnNodeExpectedReport(
    nodeIds                 : Seq[NodeId]
  , directiveExpectedReports: Seq[DirectiveExpectedReports]
) extends HashcodeCaching {}


object ConfigurationExecutionBatch {
  final val matchCFEngineVars = """.*\$(\{.+\}|\(.+\)).*""".r
  final private val replaceCFEngineVars = """\$\{.+\}|\$\(.+\)"""

  /**
   * Takes a string, that should contains a CFEngine var ( $(xxx) or ${xxx} )
   * replace the $(xxx) (or ${xxx}) part by .*
   * and doubles all the \
   * Returns a string that is suitable for a beoing used as a regexp
   */
  final def replaceCFEngineVars(x : String) : String = {
    x.replaceAll(replaceCFEngineVars, ".*").replaceAll("""\\""", """\\\\""")
  }
}

/**
 * The execution batch for a rule, still a lot of intelligence to add within
 *
 */
case class ConfigurationExecutionBatch(
    val ruleId                  : RuleId
  , val serial                  : Int
  , val directivesOnNodesExpectedReports : Seq[DirectivesOnNodeExpectedReport]
  , val executionTime           : DateTime
  , val executionReports        : Seq[Reports]
  , val beginDate               : DateTime
  , val endDate                 : Option[DateTime]
  , val agentExecutionInterval : Int
) extends ExecutionBatch with Loggable {

  // A cache of the already computed values
  val cache = scala.collection.mutable.Map[String, Seq[NodeId]]()

  val nodeStatus = scala.collection.mutable.Map[String, Seq[NodeStatusReport]]()

  /**
   * This is the main entry point to get the detailed reporting
   * It returns a Sequence of NodeStatusReport which gives, for
   * each node, the status and all the directives associated
   *
   */
  def getNodeStatus() : Seq[NodeStatusReport] = {

    nodeStatus.getOrElseUpdate("nodeStatus",
      (for {
        nodeId <- directivesOnNodesExpectedReports.flatMap(x => x.nodeIds).distinct
        nodeFilteredReports = executionReports.filter(x => (x.nodeId==nodeId))

        directiveStatusReports = for {
          expectedDirective <- directivesOnNodesExpectedReports.filter(x => x.nodeIds.contains(nodeId)).flatMap(x => x.directiveExpectedReports)
          directiveFilteredReports = nodeFilteredReports.filter(x => x.directiveId == expectedDirective.directiveId)

          // look for each component
          componentsStatus = for {
            expectedComponent <- expectedDirective.components
            componentFilteredReports = directiveFilteredReports.filter(x => x.component == expectedComponent.componentName)

            componentStatusReport = checkExpectedComponentWithReports(
                    expectedComponent
                  , componentFilteredReports
                  , nodeId
            )
          } yield {
            componentStatusReport
          }

          directiveStatusReport = DirectiveStatusReport(
              expectedDirective.directiveId
            , componentsStatus
            , Seq()
          )

        } yield {
          directiveStatusReport
        }

        nodeStatusReport = NodeStatusReport(
            nodeId
          , ruleId
          , directiveStatusReports
          , Seq()
        )
      } yield {
        nodeStatusReport
      })
    )
  }

  protected[bean] def checkExpectedComponentWithReports(
      expectedComponent : ReportComponent
    , filteredReports   : Seq[Reports]
    , nodeId            : NodeId
  ) : ComponentStatusReport = {

    // easy case : No Reports mean no answer
    filteredReports.size match {
      case 0 =>
        val components = for {
          (component, unexpanded) <- expectedComponent.groupedComponentValues
        } yield {
          ComponentValueStatusReport(
              component
            , unexpanded
            , getNoAnswerOrPending()
            , Nil
            , nodeId
          )
        }
        ComponentStatusReport(
            expectedComponent.componentName
          , components
          , Nil
          , Set()
        )
      case _ =>
        // First, filter out all the not interesting reports
        val purgedReports = filteredReports.filter(x => x.isInstanceOf[ResultErrorReport]
                               || x.isInstanceOf[ResultRepairedReport]
                               || x.isInstanceOf[ResultSuccessReport]
                               || x.isInstanceOf[ResultNotApplicableReport]
                               || x.isInstanceOf[UnknownReport])

        val components = for {
            (componentValue, unexpandedComponentValues) <- expectedComponent.groupedComponentValues
            (status,message) = checkExpectedComponentStatus(
                              componentValue
                            , purgedReports
                            , expectedComponent.componentsValues
                            , expectedComponent.componentsValues.filter(_ == componentValue).size
                            )
        } yield {
          ComponentValueStatusReport(
                componentValue
              , unexpandedComponentValues
              , status
              , message
              , nodeId)
        }

        // must fetch extra entries
        val unexpectedReports = getUnexpectedReports(
            expectedComponent.componentsValues.toList
          , purgedReports
        )

        unexpectedReports.size match {
          case 0 =>
            ComponentStatusReport(
                expectedComponent.componentName
              , components
              , purgedReports.map(_.message).toList
              , Set()
            )

          case _ => // some bad report
            unexpectedReports.foreach{ invalidReport =>
              ReportLogger.warn("Unexpected report for Directive %s, Rule %s generated on %s on node %s, Component is %s, keyValue is %s. The associated message is : %s".format(
                  invalidReport.directiveId
                , invalidReport.ruleId
                , invalidReport.executionTimestamp
                , invalidReport.nodeId
                , invalidReport.component
                , invalidReport.keyValue
                , invalidReport.message))
            }
            val cpvalue = for {
              unexpectedReport <- unexpectedReports
            } yield {
                ComponentValueStatusReport(
                   unexpectedReport.keyValue
                 , None // <- is it really None that we set there ?
                 , UnknownReportType
                 , List(unexpectedReport.message)
                 , unexpectedReport.nodeId
                )
            }
             ComponentStatusReport(
                expectedComponent.componentName
              , components
              , unexpectedReports.map(_.message).toList
              , cpvalue.toSet
            )
        }

    }
  }


  private[this] def returnWorseStatus(
      reports : Seq[Reports]
  ) : ReportType = {
    if (reports.exists(x => x.isInstanceOf[ResultErrorReport])) {
      ErrorReportType
    } else {
      if (reports.exists(x => x.isInstanceOf[UnknownReport])) {
        UnknownReportType
      } else {
        if (reports.exists(x => x.isInstanceOf[ResultRepairedReport])) {
          RepairedReportType
        } else {
          if (reports.exists(x => x.isInstanceOf[ResultSuccessReport])) {
            SuccessReportType
          } else {
            if (reports.exists(x => x.isInstanceOf[ResultNotApplicableReport])) {
              NotApplicableReportType
            } else {
              getNoAnswerOrPending()
            }
          }
        }
      }
    }

  }


  /*
   * An utility method that fetch the proper status and messages
   * of a component key.
   * Parameters :
   * currentValue : the current keyValue processes
   * filteredReports : the report for that component (but including all keys)
   * values : all values expected for that component, to fetch unexpected as well
   * Return:
   * a couple containing the actual status of the component key and the messages associated
   */
  protected def checkExpectedComponentStatus(
      currentValue           : String
    , purgedReports          : Seq[Reports]
    , values                 : Seq[String]
    , cardinality            : Int
  ) : (ReportType,List[String]) = {
    val unexepectedReports = purgedReports.filterNot(value => values.contains(value.keyValue))

    /* Refactored this function because it was the same behavior for each case*/
    def getComponentStatus (filteredReports:Seq[Reports]) : (ReportType,List[String]) = {
       filteredReports.filter( x => x.isInstanceOf[ResultErrorReport]).size match {
          case i if i > 0 => (ErrorReportType,filteredReports.map(_.message).toList)
          case _ => {
            filteredReports.size match {
              /* Nothing was received at all for that component so : No Answer or Pending */
              case 0 if unexepectedReports.size==0 =>  (getNoAnswerOrPending(),Nil)
              /* Reports were received for that component, but not for that key, that's a missing report */
              case 0 =>  (UnknownReportType,Nil)
              case x if x == cardinality =>
                (returnWorseStatus(filteredReports),filteredReports.map(_.message).toList)
              case _ => (UnknownReportType,filteredReports.map(_.message).toList)
            }
          }
        }
    }

    currentValue match {
      case "None" =>
        val filteredReports = purgedReports.filter( x => x.keyValue == currentValue)
        getComponentStatus(filteredReports)

      case matchCFEngineVars(_) =>
        // convert the entry to regexp, and match what can be matched
         val matchableExpected = replaceCFEngineVars(currentValue)
         val matchedReports = purgedReports.filter( x => x.keyValue.matches(matchableExpected))

         matchedReports.filter( x => x.isInstanceOf[ResultErrorReport]).size match {
           case i if i > 0 => (ErrorReportType,matchedReports.map(_.message).toList)

           case _ => {
            matchedReports.size match {
              case 0 if unexepectedReports.size==0 => (getNoAnswerOrPending(),Nil)
              case 0 =>
                (UnknownReportType,Nil)
              case x if x == values.filter( x => x.matches(matchableExpected)).size =>
                (returnWorseStatus(matchedReports),matchedReports.map(_.message).toList)
              case _ => (UnknownReportType,matchedReports.map(_.message).toList)
            }

          }

         }

      case _: String =>
          // for a given component, if the key is not "None", then we are
          // checking that what is have is what we wish
          // we can have more reports that what we expected, because of
          // name collision, but it would be resolved by the total number
        val keyReports =  purgedReports.filter( x => x.keyValue == currentValue)
        getComponentStatus(keyReports)

    }
  }

  /**
   * Retrieve all the reports that should not be there (due to
   * keyValue not present)
   */
  private[this] def getUnexpectedReports(
      keyValues      : List[String]
    , reports        : Seq[Reports]
    ) : Seq[Reports] = {
    keyValues match {
      case Nil => reports
      case head :: tail =>
        head match {
          case matchCFEngineVars(_) =>
            val matchableExpected = replaceCFEngineVars(head)
            getUnexpectedReports(
                tail
              , reports.filterNot(x => x.keyValue.matches(matchableExpected)) )
          case s: String =>
            getUnexpectedReports(
                tail
              , reports.filterNot(x => x.keyValue == s ) )
        }
    }
  }

  /**
   * Utility method to determine if we are in the pending time, or if the node hasn't answered for a long time
   * We consider it is a no answer if the agent hasn't answered for twice the run interval
   */
  private[this] def getNoAnswerOrPending() : ReportType = {
    if (beginDate.plus(agentExecutionInterval*2*1000*60).isAfter(DateTime.now())) {
      PendingReportType
    } else {
      NoAnswerReportType
    }
  }

  /**
   * Get the actual status of a Rule, it returns a list of every directive contained by that Rule
   */
  def getRuleStatus() : Seq[DirectiveRuleStatusReport]={

    directivesOnNodesExpectedReports.flatMap {  case DirectivesOnNodeExpectedReport(_, directiveExpectedReports) =>
      directiveExpectedReports
    }.groupBy( x => x.directiveId).map { case (directiveId, directiveExpectedReports) =>
        // we fetch the component reports for this directive
        val componentReports = getNodeStatus().
                      flatMap{ nodeStatus =>
          // we filter by directiveId
          val directivesStatus = nodeStatus.directives.filter(_.directiveId == directiveId)
          getComponentRuleStatus(directiveId, directiveExpectedReports.flatMap(x=> x.components), directivesStatus)
        }.groupBy(_.component).map { case (componentName, componentReport) =>

          val componentValueReports = componentReport.flatMap(_.componentValues).
            groupBy(x=> (x.unexpandedComponentValue)).
            flatMap { case (unexpandedComponentValue, componentValueReport) =>
              // if unexpandedComponentValue exists, then we may have different values, hence the worst type
              // has to be computed there; else it has to be computed on the values level
              unexpandedComponentValue match {
                case Some(unexpended) =>
                  componentValueReport.groupBy(x => x.componentValue).map { case (componentValue, reports) =>
                    ComponentValueRuleStatusReport(
                        directiveId
                      , componentName
                      , componentValue
                      , unexpandedComponentValue
                      , reports.flatMap(_.nodesReport)
                    )
                  }
                case None =>
                  componentValueReport.groupBy(x => x.componentValue).map { case (componentValue, reports) =>
                    ComponentValueRuleStatusReport(
                        directiveId
                      , componentName
                      , componentValue
                      , unexpandedComponentValue
                      , reports.flatMap(_.nodesReport)
                    )
                  }
              }
           }.toSeq
           ComponentRuleStatusReport(directiveId,componentName,componentValueReports)
        }.toSeq
        DirectiveRuleStatusReport(directiveId,componentReports)
      }.toSeq
  }
  /**
   * Get the status of every component of the directive passed as a parameter
   * Parameters:
   * directiveId : Components we are looking for are contained in that directive
   * components  : Expected component report format
   * directive   : Latest directive reports
   */
  def getComponentRuleStatus(directiveid:DirectiveId, components:Seq[ReportComponent], directiveReports:Seq[DirectiveStatusReport]) : Seq[ComponentRuleStatusReport]={

    // Make a map of all component, currently we cannot use class ReportComponent correctly since it does not bbehave correctly
    // We want to have a map that give for a Component, which unexpanded value is set, and for which unexpanded value, which values are expected
    val expectedComponents = components.
      // Group by component
      groupBy(_.componentName).
        // transform them in couple (value,unexpandedValue)
        mapValues(_.flatMap(_.groupedComponentValues).
            // Group values with the same unexpandedValue together
            groupBy(_._2).
              // Make a set of expected value so we dont have them twice
              mapValues(_.map(_._1).toSet))

     (expectedComponents.map{ case (component,expectedValues) =>
       val valueReports = directiveReports.flatMap{ directiveReports =>
         val componentReports = directiveReports.components.filter(_.component==component)
         getComponentValuesRuleStatus(directiveid,component,expectedValues,componentReports) ++
         getUnexpectedComponentValuesRuleStatus(directiveid,component,componentReports.flatMap(_.unexpectedCptValues))
       }
       ComponentRuleStatusReport(directiveid,component,valueReports)
     }).toSeq
 }
  /**
   * Get the status of expected values of the component passed as a parameter
   * Parameters:
   * directiveId : Values we are looking for are contained in that directive
   * component   : Values we are looking for are contained in that component
   * expectedValues : Expected values format, a map of expected unexpanded value => real value
   * componentReports : Latest components report
   */
 def getComponentValuesRuleStatus (
     directiveid      : DirectiveId
   , component        : String
   , expectedValues   : Map[Option[String], Set[String]]
   , componentReports : Seq[ComponentStatusReport]
 ) : Set[ComponentValueRuleStatusReport]={

   (expectedValues.flatMap{
     case (unexpandedValue, realValues) =>
       // Get all reports that match values from expanded value
       val reports = {
         for {
           componentReports <- componentReports
           valueReport <- componentReports.componentValues
           if (realValues.contains(valueReport.componentValue))
         } yield {
           valueReport
         }
       }
       for {
         // Regroup all reports by component Value
         (componentValue,reportByValues) <- reports.groupBy(_.componentValue)
       } yield {
         val reports = reportByValues.map(value => NodeReport(value.nodeId,value.reportType,value.message))
         ComponentValueRuleStatusReport(
             directiveid
           , component
           , componentValue
           , unexpandedValue
           , reports
         )
       }
     }).toSet

 }

   /**
   * Get the status of expected values of the component passed as a parameter
   * Parameters:
   * directiveId : Unexpected Values have been received for that directive
   * component   : Unexpected Values have been received for that component
   * values      : Unexpected values received for that component
   */
 def getUnexpectedComponentValuesRuleStatus(directiveid:DirectiveId, component:String, values:Seq[ComponentValueStatusReport]) : Seq[ComponentValueRuleStatusReport]={
     values.map{
       value =>
         val nodes = Seq(NodeReport(value.nodeId,value.reportType,value.message))
         ComponentValueRuleStatusReport(
             directiveid
           , component
           , value.componentValue
           , value.unexpandedComponentValue
           , nodes
         )
     }
 }

}

trait StatusReport {
  def reportType : ReportType
}
/**
 * For a component value, store the report status
 */
case class ComponentValueStatusReport(
    componentValue 		       : String
  , unexpandedComponentValue : Option[String]
  , reportType               : ReportType
  , message                  : List[String]
  , nodeId	                 : NodeId
) extends StatusReport

/**
 * For a component, store the report status, as the worse status of the component
 * Or error if there is an unexpected component value
 */
case class ComponentStatusReport(
    component           : String
  , componentValues     : Set[ComponentValueStatusReport]
  , message             : List[String]
  , unexpectedCptValues : Set[ComponentValueStatusReport]
) extends StatusReport {

  val reportType = {
    val reports = (componentValues ++ unexpectedCptValues).map(_.reportType)
    ReportType.getWorseType(reports)
  }
}


case class DirectiveStatusReport(
    directiveId          : DirectiveId
  , components	         : Seq[ComponentStatusReport]
  , unexpectedComponents : Seq[ComponentStatusReport] // for future use, not used yet
) extends StatusReport {

  val reportType = {
    val reports = (components ++ unexpectedComponents).map(_.reportType)
    ReportType.getWorseType(reports)
  }
}

case class NodeStatusReport(
    nodeId               : NodeId
  , ruleId               : RuleId
  , directives	         : Seq[DirectiveStatusReport]
  , unexpectedDirectives : Seq[DirectiveStatusReport] // for future use, not used yet
) extends StatusReport {

  val reportType = {
    val reports = (directives ++ unexpectedDirectives).map(_.reportType)
    ReportType.getWorseType(reports)
  }
}

case class NodeReport (
    node       : NodeId
  , reportType : ReportType
  , message    : List[String]
)

sealed trait RuleStatusReport {

  def nodesReport : Seq[NodeReport]

  lazy val reportType = {
    val reports = nodesReport.map(_.reportType)
    ReportType.getWorseType(reports)
  }

  def processMessageReport(filter: NodeReport => Boolean):Seq[MessageReport]

  def computeCompliance : Option[Int] = {
    if (nodesReport.size>0){
      val reportsSize = nodesReport.size.toDouble
      Some((nodesReport.map(report => report.reportType match {
        case SuccessReportType => 1
        case NotApplicableReportType    => 1
        case _                 => 0
    }):\ 0)((res:Int,value:Int) => res+value) * 100 / reportsSize).map{ res =>
      BigDecimal(res).setScale(0,BigDecimal.RoundingMode.HALF_UP).toInt
      }
    }
    else {
      None
    }
  }
}

case class ComponentValueRuleStatusReport(
    directiveId         : DirectiveId
  , component           : String
  , componentValue      : String
  , unexpandedComponentValue : Option[String]
  , nodesReport             : Seq[NodeReport]
) extends RuleStatusReport {


  // Key of the component, get the unexpanded value if it exists or else the component value
  val key = unexpandedComponentValue.getOrElse(componentValue)

  def processMessageReport(filter: NodeReport => Boolean):Seq[MessageReport] ={
    nodesReport.filter(filter).map(MessageReport(_,component,componentValue, unexpandedComponentValue))
  }
}

case class ComponentRuleStatusReport (
    directiveId         : DirectiveId
  , component           : String
  , componentValues     : Seq[ComponentValueRuleStatusReport]
) extends RuleStatusReport {

  override val nodesReport = componentValues.flatMap(_.nodesReport)

  // since we have "exploded" ComponentValue, we need to regroup them
  override def computeCompliance = {
   if (componentValues.size>0){
     // we need to group the compliances per unexpandedComponentValue
     val aggregatedComponents = componentValues.groupBy { entry => entry.unexpandedComponentValue.getOrElse(entry.componentValue)}.map { case (key, entries) =>
       ComponentValueRuleStatusReport(
             entries.head.directiveId // can't fail because we are in a groupBy
           , entries.head.component  // can't fail because we are in a groupBy
           , key
           , None
           , entries.flatMap(_.nodesReport)
          )
     }
     Some((aggregatedComponents.map(_.computeCompliance.getOrElse(0))
         :\ 100)((res:Int,value:Int) => if(value>res)res else value))
   }
    else
      None
  }

  def processMessageReport(filter: NodeReport => Boolean):Seq[MessageReport] = {
    componentValues.flatMap( value => value.processMessageReport(filter))
  }
}

case class DirectiveRuleStatusReport(
    directiveId          : DirectiveId
  , components           : Seq[ComponentRuleStatusReport]
) extends RuleStatusReport {

  override val nodesReport = components.flatMap(_.nodesReport)

  override def computeCompliance =
   if (components.size>0){
     Some((components.map(_.computeCompliance.getOrElse(0))
         :\ 100)((res:Int,value:Int) => if(value>res)res else value))
   }
    else
      None

  def processMessageReport(filter: NodeReport => Boolean):Seq[MessageReport] = {
    components.flatMap( component => component.processMessageReport(filter))
  }
}


  case class MessageReport(
        report          : NodeReport
      , component       : String
      , value           : String
      , unexpandedValue : Option[String]
  )