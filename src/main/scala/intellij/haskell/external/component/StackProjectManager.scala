/*
 * Copyright 2014-2017 Rik van der Kleij
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

package intellij.haskell.external.component

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.progress.{PerformInBackgroundOption, ProgressIndicator, ProgressManager, Task}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFileManager
import intellij.haskell.external.execution.StackCommandLine
import intellij.haskell.external.execution.StackCommandLine.build
import intellij.haskell.external.repl.StackReplsManager
import intellij.haskell.module.HaskellModuleBuilder
import intellij.haskell.util.HaskellProjectUtil
import intellij.haskell.{GlobalInfo, HaskellNotificationGroup}

object StackProjectManager {

  import intellij.haskell.util.ScalaUtil._

  def isInitializing(project: Project): Boolean = {
    getStackProjectManager(project).exists(_.initializing)
  }

  def isBuilding(project: Project): Boolean = {
    getStackProjectManager(project).exists(_.building)
  }

  def isHoogleAvailable(project: Project): Boolean = {
    getStackProjectManager(project).exists(_.hoogleAvailable)
  }

  def isHlintAvailable(project: Project): Boolean = {
    getStackProjectManager(project).exists(_.hlintAvailable)
  }

  def start(project: Project): Unit = {
    init(project)
  }

  def restart(project: Project): Unit = {
    init(project, restart = true)
  }

  def getStackProjectManager(project: Project): Option[StackProjectManager] = {
    project.isDisposed.optionNot(project.getComponent(classOf[StackProjectManager]))
  }

  private def init(project: Project, restart: Boolean = false): Unit = {
    if (HaskellProjectUtil.isValidHaskellProject(project, notifyNoSdk = true)) {
      if (isInitializing(project)) {
        HaskellNotificationGroup.logWarningBalloonEvent(project, "Action is not possible because project is initializing")
      } else {
        HaskellNotificationGroup.logInfoEvent(project, "Initializing Haskell project")

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Building project, starting REPL(s), building tools and preloading cache", false, PerformInBackgroundOption.ALWAYS_BACKGROUND) {

          def run(progressIndicator: ProgressIndicator) {
            getStackProjectManager(project).foreach(_.initializing = true)
            getStackProjectManager(project).foreach(_.building = true)
            try {
              try {
                progressIndicator.setText("Busy with updating the Stack package index")
                StackCommandLine.executeInMessageView(project, Seq("update"))

                progressIndicator.setText("Busy with building project")

                val result = StackCommandLine.buildProjectDependenciesInMessageView(project)
                if (result.contains(true)) {
                  StackCommandLine.buildProjectInMessageView(project)
                } else {
                  HaskellNotificationGroup.logInfoEvent(project, "Project will not be built because building it's dependencies failed")
                }

                if (restart) {
                  val projectRepsl = StackReplsManager.getRunningProjectRepls(project)
                  progressIndicator.setText("Busy with stopping Stack REPLs")
                  StackReplsManager.getGlobalRepl(project).foreach(_.exit())
                  projectRepsl.foreach(_.exit())

                  progressIndicator.setText("Busy with cleaning up cache")
                  HaskellComponentsManager.invalidateGlobalCaches(project)

                  ApplicationManager.getApplication.runReadAction(new Runnable {

                    override def run(): Unit = {
                      getStackProjectManager(project).foreach(_.initStackReplsManager())
                    }
                  })
                }

                progressIndicator.setText(s"Busy with building Intero ")
                build(project, Seq("intero"), logBuildResult = true)

                progressIndicator.setText("Busy with starting global Stack REPL")
                StackReplsManager.getGlobalRepl(project).foreach(_.start())
              } finally {
                getStackProjectManager(project).foreach(_.building = false)
                if (!project.isDisposed) {
//                  val stackLibraryComponents = StackReplsManager.getReplsManager(project).map(_.stackComponentInfos.filter(_.stanzaType == LibType)).getOrElse(Iterable())
                  ApplicationManager.getApplication.getMessageBus.connect(project).subscribe(VirtualFileManager.VFS_CHANGES, new ProjectLibraryFileWatcher(project))
                }
              }

              progressIndicator.setText("Busy with downloading library sources")
              HaskellModuleBuilder.addLibrarySources(project)

              progressIndicator.setText("Busy with preloading libraries")
              val preloadCacheFuture = ApplicationManager.getApplication.executeOnPooledThread(new Runnable {

                override def run(): Unit = {
                  HaskellComponentsManager.preloadLibraryIdentifiersCaches(project)

                  HaskellNotificationGroup.logInfoEvent(project, "Restarting global REPL to release memory")
                  StackReplsManager.getGlobalRepl(project).foreach(_.restart())
                }
              })

              progressIndicator.setText(s"Busy with installing ${HLintComponent.HLintName} in ${GlobalInfo.toolsBinPath}")
              StackCommandLine.installTool(project, HLintComponent.HLintName)
              getStackProjectManager(project).foreach(_.hlintAvailable = true)

              progressIndicator.setText(s"Busy with installing ${HoogleComponent.HoogleName} in ${GlobalInfo.toolsBinPath}")
              StackCommandLine.installTool(project, HoogleComponent.HoogleName)
              getStackProjectManager(project).foreach(_.hoogleAvailable = true)


              if (!HoogleComponent.doesHoogleDatabaseExist(project)) {
                HoogleComponent.showHoogleDatabaseDoesNotExistNotification(project)
              }

              progressIndicator.setText("Busy with preloading libraries")
              if (!preloadCacheFuture.isDone) {
                preloadCacheFuture.get
              }
            }
            finally {
              getStackProjectManager(project).foreach(_.initializing = false)
            }
          }
        })
      }
    }
  }
}

class StackProjectManager(project: Project) extends ProjectComponent {

  override def getComponentName: String = "stack-project-manager"

  @volatile
  private var initializing = false

  @volatile
  private var building = false

  @volatile
  private var hoogleAvailable = false

  @volatile
  private var hlintAvailable = false

  @volatile
  private var replsManager: Option[StackReplsManager] = None

  def getStackReplsManager: Option[StackReplsManager] = {
    replsManager
  }

  def initStackReplsManager(): Unit = {
    replsManager = Option(new StackReplsManager(project))
  }

  override def projectClosed(): Unit = {
    if (HaskellProjectUtil.isHaskellProject(project)) {
      replsManager.foreach(_.getGlobalRepl.exit())
      replsManager.foreach(_.getRunningProjectRepls.foreach(_.exit()))
    }
  }

  override def initComponent(): Unit = {}

  override def projectOpened(): Unit = {
    if (HaskellProjectUtil.isHaskellProject(project)) {
      initStackReplsManager()
      if (replsManager.exists(_.stackComponentInfos.isEmpty)) {
        Messages.showErrorDialog(project, s"Can not start project because no Cabal file was found or could not be read", "Can not start project")
      } else {
        StackProjectManager.start(project)
      }
    }
  }

  override def disposeComponent(): Unit = {}
}