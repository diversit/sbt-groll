/*
 * Copyright 2015 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.heikoseeberger.sbtgroll

import com.typesafe.config.{ ConfigException, ConfigFactory }
import org.eclipse.jgit.api.errors.GitAPIException
import sbt.{ Keys, Project, State }

private object Groll {
  def apply(state: State, grollArg: GrollArg): State =
    new Groll(state, grollArg).apply()
}

private class Groll(state: State, grollArg: GrollArg) {

  val BuildDefinition = """.+sbt|project/.+\.scala|project/.+\.sbt"""

  val baseDirectory = Project.extract(state).get(Keys.baseDirectory)

  val configFile = Project.extract(state).get(GrollKey.grollConfigFile)

  val historyRef = Project.extract(state).get(GrollKey.grollHistoryRef)

  val workingBranch = Project.extract(state).get(GrollKey.grollWorkingBranch)

  val git = Git(baseDirectory)

  val (currentId, currentMessage) = git.current()

  def apply(): State = {
    if (!git.existsRef(historyRef)) {
      state.log.error(s"""There's no "$historyRef" tag or branch as defined with the `grollHistoryRef` setting!""")
      state
    } else {
      val history = git.history(historyRef)
      grollArg match {
        case GrollArg.Show =>
          ifCurrentInHistory(history) {
            state.log.info(s"== $currentId $currentMessage")
            state
          }
        case GrollArg.List =>
          for ((id, message) <- history) {
            if (id == currentId)
              state.log.info(s"== $id $message")
            else
              state.log.info(s"   $id $message")
          }
          state
        case GrollArg.Next =>
          ifCurrentInHistory(history) {
            groll(
              history.takeWhile { case (id, _) => id != currentId }.lastOption,
              "Already at the head of the commit history!",
              (id, message) => s">> $id $message"
            )
          }
        case GrollArg.Prev =>
          ifCurrentInHistory(history) {
            groll(
              history.dropWhile { case (id, _) => id != currentId }.tail.headOption,
              "Already at the first commit!",
              (id, message) => s"<< $id $message"
            )
          }
        case GrollArg.Head =>
          groll(
            if (currentId == history.head._1)
              None
            else
              Some(history.head),
            "Already at the head of the commit history!",
            (id, message) => s">> $id $message"
          )
        case GrollArg.Initial =>
          groll(
            history.find { case (_, message) => message.contains("groll:initial") || message.startsWith("Initial state") },
            """There's no commit with a message containing "groll:initial" or starting with "Initial state"!""",
            (id, message) => s"<< $id $message"
          )
        case GrollArg.Move(id) =>
          groll(
            if (currentId == id)
              None
            else
              Some(id -> history.toMap.getOrElse(id, "")),
            s"""Already at "$id"""",
            (id, message) => s"<> $id $message"
          )
        case GrollArg.Push(branch) =>
          if (!configFile.exists())
            state.log.error(s"""Configuration file "$configFile" not found!""")
          else if (!git.existsRef(workingBranch))
            state.log.warn(s"""There's no working branch "$workingBranch": Have you used `groll initial`?""")
          else {
            try {
              val config = ConfigFactory.parseFile(configFile)
              val username = config.getString("username")
              val password = config.getString("password")
              git.pushHead(workingBranch, branch, username, password)
              state.log.info(s"""Pushed current state to branch "$branch"""")
            } catch {
              case e: ConfigException => state.log.error(s"""Could not read username and password from configuration file "$configFile"!""")
              case e: GitAPIException => state.log.error(s"Git error: ${e.getMessage}")
            }
          }
          state
        case GrollArg.Version =>
          state.log.info(BuildInfo.version)
          state
      }
    }
  }

  def ifCurrentInHistory(history: Seq[(String, String)])(action: => State): State = {
    if (!history.map(fst).contains(currentId)) {
      state.log.warn(s"""Current commit "$currentId" is not within the history defined by "$historyRef": Use "head", "initial" or "move"!""")
      state
    } else
      action
  }

  def groll(idAndMessage: Option[(String, String)], warn: => String, info: (String, String) => String): State =
    idAndMessage match {
      case None =>
        state.log.warn(warn)
        state
      case Some((id, message)) =>
        git.resetHard()
        git.clean()
        git.checkout(id, workingBranch)
        state.log.info(info(id, message))
        if (git.diff(id, currentId).exists(_.matches(BuildDefinition)))
          state.reload
        else
          state
    }
}
