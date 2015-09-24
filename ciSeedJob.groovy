// Import required dependencies
import groovy.sql.Sql
import java.util.Date
import java.text.SimpleDateFormat

/*
 * THIS IS AN EXAMPLE SNIPPET. FOR MORE DETAILS SEE THE FOLLOWING BLOG ARTICLE:
 * https://blog.codecentric.de/en/?p=30502
 *
 * This Jenkins Job DSL Groovy Script creates Continuous Integration (CI) Jobs
 * for all Maven & Ant projects that exist on a GitLab Server.
 *
 * The script does the following steps:
 * 1. Call GitLab REST API and get all projects
 * 2. Call Configuration Management Database via JDBC and read additional information
 * 3. Iterate over all projects and get further project details via GitLab REST API
 * 4. Create all necessary jobs via Jenkins Job DSL. Typical jobs are: Build, Deploy, Acceptance Test-Jobs
 * 5. Create custom views
 */

// GitLab settings
gitUrl = 'http://git/api/v3'
// you find the private token in your GitLab profile
gitPrivateToken = 'xYxYxYxYxYxYxYxYxYxY'
// id of Jenkins GitLab credentials
gitCredentials = "xYxYxYxY-xYxY-xYxY-xYxY-xYxYxYxYxYxY"

// mapping of Java Version to Jenkins JDK Version
jdkMap = ['6': 'JDK 6', '7': 'JDK 7', '8': 'JDK 8', '9': 'JDK 9']
javacMap = ['6': '1.6', '7': '1.7', '8': '1.8', '9': '1.9']

// Default build tool versions
def antVersion = "Ant 1.9.6"
def mavenVersion = "Maven 3.3.3"

// valid company department id's
departments = ["BIZ1","BIZ2","BIZ3","DEV1","DEV2","DEV3","OPS1","OPS2"]

// GitLab REST API settings. REST API returns max 100 per page. Thats why we need pagination.
def currentPage = 1
def projectsPerPage = 100
def currentProjectsSize = 100

// we will use this array to gather all CI job names
def ciJobList = []

// read projects from GitLab REST API until finished
while (currentProjectsSize == projectsPerPage) {

  def projectsApi = new URL("${gitUrl}/projects/all?page=${currentPage}&per_page=${projectsPerPage}&private_token=${gitPrivateToken}")

  println "############################################################################################################"
  println "Read GitLab REST API: ${projectsApi}"
  println "############################################################################################################"

  // convert returned JSON to object
  def projects = new groovy.json.JsonSlurper().parse(projectsApi.newReader())

  // required for pagination
  currentProjectsSize = projects.size()
  currentPage++

  // iterate over all projects
  projects.each {

    println "------------------------------------------------------------------------------------------------------------"
    println "Working on project: ${it.name}"

    def gitProjectName = it.name
    def projectId = it.id
    def projectGitSshUrlToRepo = it.ssh_url_to_repo

    // read project details from configuration management databse
    def projectDetails = getProjectDetailsFromConfigMgtDb(gitProjectName)

    def isJavaProject=projectDetails.isJavaProject
    if ( ! isJavaProject ) {
      println "-> skipping project, since no Config Mgt details are available."
      return
    }

    def webContextRoot=projectDetails.webContextRoot
    def mailRecipients=projectDetails.mailRecipients
    def isAnt=projectDetails.isAnt
    def isMaven=projectDetails.isMaven
    def isLibrary=projectDetails.isLibrary
    def isBatch=projectDetails.isBatch
    def isWebApp=projectDetails.isWebApp
    def isArchetype=projectDetails.isArchetype
    def javaVersion = projectDetails.javaVersion

    // get JDK Version and javac Version from javaVersion in Config Mgt DB
    def jenkinsJdkVersion = jdkMap.get(javaVersion)
    def projectJavacVersion = javacMap.get(javaVersion)

    println "-> Properties:"
    println "-> javaVersion=${javaVersion}"
    println "-> projectJavacVersion=${projectJavacVersion}"
    println "-> jenkinsJdkVersion=${jenkinsJdkVersion}"
    println "-> isMaven=${isMaven}"
    println "-> isAnt=${isAnt}"
    println "-> webContextRoot=${webContextRoot}"
    println "-> mailRecipients=${mailRecipients}"

    if ( javaVersion == null || projectJavacVersion == null || jenkinsJdkVersion == null ) {
      println "-> ERROR: Should never get here."
      return
    }

    // get additional information from GitLab repository
    def gitProjectDetails = getGitProjectDetails(projectId)
    def validProjectGruppe = gitProjectDetails.validProjectGruppe
    def isGitProjectActive = gitProjectDetails.isActive
    def defaultBranch = gitProjectDetails.defaultBranch

    println "-> isGitProjectActive=${isGitProjectActive}"
    println "-> validProjectGruppe=${validProjectGruppe}"
    println "-> defaultBranch=${defaultBranch}"

    if( ! isGitProjectActive || defaultBranch == null ) {
      println "Skipping project ${gitProjectName} since it is not active or does not have a default branch"
      return
    }

    // each project is assigned a department, therefore all jobs are prefixed with the department id
    println "-> Create Job names:"
    def ciJobName = "${validProjectGruppe}-${gitProjectName}-1-ci"
    def deployJobName = "${validProjectGruppe}-${gitProjectName}-2-deployment"
    def robotJobName = "${validProjectGruppe}-${gitProjectName}-3-robot"

    // create CI jobs
    if(isLibrary && isMaven) {
      println "-> create Maven Library Job: ${ciJobName}"
      createCIJobOnly(ciJobName, projectGitSshUrlToRepo, defaultBranch, mailRecipients, jenkinsJdkVersion)
    } else if(isBatch && isMaven) {
      println "-> create Maven Batch Job: ${ciJobName}"
      createCIJobOnly(ciJobName, projectGitSshUrlToRepo, defaultBranch, mailRecipients, jenkinsJdkVersion)
    } else if(isBatch && isAnt) {
      println "-> create Ant Batch Job: ${ciJobName}"
      createAntCIJobOnly(ciJobName, gitProjectName, defaultBranch, projectGitSshUrlToRepo, webContextRoot, mailRecipients, jenkinsJdkVersion, projectJavacVersion)
    } else if(isMaven && isWebApp ) {
      println "-> create Maven WebApp Job: ${ciJobName}"
      createMavenCIJob(ciJobName, projectGitSshUrlToRepo, defaultBranch, deployJobName, mailRecipients, jenkinsJdkVersion)
    } else if (isAnt && isWebApp) {
      println "-> create Ant WebApp Job: ${ciJobName}"
      createAntCIJob(ciJobName, gitProjectName, defaultBranch, projectGitSshUrlToRepo, webContextRoot, deployJobName, mailRecipients, jenkinsJdkVersion, projectJavacVersion)
    } else if ( isArchetype ) {
      createCIJobOnly(ciJobName, projectGitSshUrlToRepo, defaultBranch, mailRecipients, jenkinsJdkVersion)
    } else {
      println "No CI Jobs will be generated for ${gitProjectName}"
    }

    // create deployment and acceptance test jobs
    if( isWebApp ) {
      println "-> create Deploy Job: ${deployJobName}"
      createDeployJob(deployJobName, projectGitSshUrlToRepo, defaultBranch, robotJobName, gitProjectName, mailRecipients)

      println "-> create Robot Job: ${robotJobName}"
      createRobotJob(projectGitSshUrlToRepo, robotJobName, gitProjectName, mailRecipients)

      // add CI Job to list
      ciJobList += ciJobName
    }
  }
}


/*
 * CREATE VIEWS TO ORGANIZE JOBS
 */

// regular expression prefix for all company departments
def groupPrefix="("
departments.each {
  groupPrefix += it+"|"
}
groupPrefix+="NOGROUP)"

createListView('1-Build', 'All Build Jobs', "${groupPrefix}.*-1-ci")
createListView('2-Deploy', 'All Deploy Jobs', "${groupPrefix}.*-2-deployment")
createListView('3-Robot', 'All Robot Jobs', "${groupPrefix}.*-3-robot")
createListView('WebApp', 'All project Jobs', "${groupPrefix}-.*-webapp")
createListView('Batch', 'All Subproject Jobs', "${groupPrefix}-.*-batch-.*")
createListView('Library', 'All Library Jobs', "${groupPrefix}-library-.*")
createListView('Admin', 'All Admin Jobs', 'Administration.*')

createNestedDepartmentView('Department')

nestedView('Build Pipelines') {
  description('Automatically generated Build Pipelines for all CI Jobs')
  columns {
    weather()
  }
  views {
    ciJobList.each {
      def job = it
      println "Create Build Pipeline View for ${job}"
       view(job, type: BuildPipelineView) {
        selectedJob(job)
        triggerOnlyLatestJob(true)
        alwaysAllowManualTrigger(true)
        showPipelineParameters(true)
        showPipelineParametersInHeaders(true)
        showPipelineDefinitionHeader(true)
        startsWithParameters(true)
        displayedBuilds(5)
      }
    }
  }
}


/*
 * JOB DSL UTILITY METHODS
 */

// generate Ant -1-ci Job
def createAntCIJobOnly(def ciJobName, def gitProjectName, def defaultBranch, def projectGitSshUrlToRepo, def webContextRoot, def mailRecipients, def projectJenkinsJdkVersion, def projectJavacVersion) {
  job(ciJobName) {
    logRotator {
      daysToKeep(-1)
      numToKeep(10)
    }
    parameters {
      stringParam("project.name", gitProjectName)
      stringParam("project.version", defaultBranch)
      stringParam("web.context.root", webContextRoot)
      booleanParam("junit.skip.tests", false)
      stringParam("javac.version", projectJavacVersion)
    }
    label("ci-slave")
    jdk(projectjenkinsJdkVersion)
    scm {
      git {
        remote {
          url(projectGitSshUrlToRepo)
          credentials(gitCredentials)
        }
        wipeOutWorkspace(true)
        createTag(false)
        branch('\${project.version}')
      }
    }
    triggers {
      cron("H * * * 1-5")
    }
    wrappers {
      preBuildCleanup()
    }
    steps {
      ant("build") {
        buildFile "build.xml"
        antInstallation antVersion
        javaOpt("-Xmx1G -XX:MaxPermSize=512M")
      }
    }
    publishers {
      chucknorris()
      archiveJunit("results/junit/**/*.xml")
      mailer(mailRecipients, true, true)
    }
  }
}

// generate Ant -1-ci Job for WebApps
def createAntCIJob(def ciJobName, def gitProjectName, def defaultBranch, def projectGitSshUrlToRepo, def webContextRoot, def deployJobName, def mailRecipients, def projectjenkinsJdkVersion, def projectJavacVersion) {
  job(ciJobName) {
    logRotator {
      daysToKeep(-1)
      numToKeep(10)
    }
    parameters {
      stringParam("project.name", gitProjectName)
      stringParam("project.version", defaultBranch)
      stringParam("web.context.root", webContextRoot)
      booleanParam("junit.skip.tests", false)
      stringParam("javac.version", projectJavacVersion)
    }
    label("ci-slave")
    jdk(projectjenkinsJdkVersion)
    scm {
      git {
        remote {
          url(projectGitSshUrlToRepo)
          credentials(gitCredentials)
        }
        wipeOutWorkspace(true)
        createTag(false)
        branch('\${project.version}')
      }
    }
    triggers {
      cron("H * * * 1-5")
    }
    wrappers {
      preBuildCleanup()
    }
    steps {
      ant("build") {
        buildFile "build.xml"
        antInstallation antVersion
        javaOpt("-Xmx1G -XX:MaxPermSize=512M")
      }
      }
    publishers {
      chucknorris()
      archiveJunit("results/junit/**/*.xml")
      downstreamParameterized {
        trigger(deployJobName, 'UNSTABLE_OR_BETTER') {
          currentBuild()
        }
      }
      mailer(mailRecipients, true, true)
    }
  }
}

// generate Maven -1-ci Job for Library or Batch projects
def createCIJobOnly(def ciJobName, def projectGitSshUrlToRepo, def defaultBranch, def mailRecipients, def projectjenkinsJdkVersion) {
  job(ciJobName) {
    logRotator {
      daysToKeep(-1)
      numToKeep(10)
    }
    parameters {
      stringParam("project.version", defaultBranch, "Select branch to build: master, branch, tag")
    }
    label("ci-slave")
    jdk(projectjenkinsJdkVersion)
    scm {
      git {
        remote {
          url(projectGitSshUrlToRepo)
          credentials(gitCredentials)
        }
        wipeOutWorkspace(true)
        createTag(false)
        branch('\${project.version}')
      }
    }
    triggers {
      scm('H/5 * * * *')
    }
    wrappers {
      preBuildCleanup()
    }
    steps {
      maven {
        goals('clean versions:set -DnewVersion=DEV-\${project.version}-\${BUILD_NUMBER} -P jenkins-build -U')
        mavenOpts('-Xms256m')
        mavenOpts('-Xmx512m')
        mavenInstallation(mavenVersion)
      }
      maven {
        goals('org.jacoco:jacoco-maven-plugin:prepare-agent deploy -P jenkins-build,sonar -Dmaven.test.failure.ignore=true')
        mavenOpts('-XX:PermSize=256m')
        mavenOpts('-XX:MaxPermSize=1024m')
        mavenInstallation(mavenVersion)
      }
      maven {
        goals('sonar:sonar -P sonar,jenkins-build')
        mavenOpts('-Xmx2G')
        mavenOpts('-XX:MaxPermSize=1G')
        mavenInstallation(mavenVersion)
      }
    }
    publishers {
      chucknorris()
      archiveJunit('**/target/surefire-reports/*.xml')
      mailer(mailRecipients, true, true)
    }
  }
}

// generate Maven -1-ci Job for WebApps
def createMavenCIJob(def ciJobName, def projectGitSshUrlToRepo, def defaultBranch, def deployJobName, def mailRecipients, def projectjenkinsJdkVersion) {
  job(ciJobName) {
    logRotator {
      daysToKeep(-1)
      numToKeep(10)
    }
    parameters {
      stringParam("project.version", defaultBranch, "Select branch to build: master, branch, tag")
    }
    label("ci-slave")
    jdk(projectjenkinsJdkVersion)
    scm {
      git {
        remote {
          url(projectGitSshUrlToRepo)
          credentials(gitCredentials)
        }
        wipeOutWorkspace(true)
        createTag(false)
        branch('\${project.version}')
      }
    }
    triggers {
      scm('H/5 * * * *')
    }
    wrappers {
      preBuildCleanup()
    }
    steps {
      maven {
        goals('clean versions:set -DnewVersion=DEV-\${project.version}-\${BUILD_NUMBER} -P jenkins-build -U')
        mavenOpts('-Xms256m')
        mavenOpts('-Xmx512m')
        mavenInstallation(mavenVersion)
      }
      maven {
        goals('org.jacoco:jacoco-maven-plugin:prepare-agent deploy -P jenkins-build,sonar -Dmaven.test.failure.ignore=true')
        mavenOpts('-XX:PermSize=256m')
        mavenOpts('-XX:MaxPermSize=1024m')
        mavenInstallation(mavenVersion)
      }
      maven {
        goals('sonar:sonar -P sonar,jenkins-build')
        mavenOpts('-Xmx2G')
        mavenOpts('-XX:MaxPermSize=1G')
        mavenInstallation(mavenVersion)
      }

    }
    publishers {
      chucknorris()
      archiveJunit('**/target/surefire-reports/*.xml')
      downstreamParameterized {
        trigger(deployJobName, 'UNSTABLE_OR_BETTER') {
          currentBuild()
        }
      }
      mailer(mailRecipients, true, true)
    }
  }
}

// generate Deployment -2-deploy Job
def createDeployJob(def deployJobName, def projectGitSshUrlToRepo, def defaultBranch, def robotJobName, def gitProjectName, def mailRecipients ) {
  job(deployJobName) {
    logRotator {
      daysToKeep(-1)
      numToKeep(10)
    }
    label("ci-slave")
    multiscm {
      git {
        remote {
          url('git@git:infrastructure/deployment-scripts.git')
          credentials(gitCredentials)
        }
        createTag(false)
        branch("master")
      }
      git {
        remote {
          url(projectGitSshUrlToRepo)
          credentials(gitCredentials)
        }
        createTag(false)
        branch(defaultBranch)
        relativeTargetDir(gitProjectName)
      }
    }
    wrappers {
      preBuildCleanup()
    }
    steps {
      shell("set -x && sh startDeployment.sh ${gitProjectName}")
    }
    publishers {
      chucknorris()
      downstreamParameterized {
        trigger(robotJobName, 'UNSTABLE_OR_BETTER') {
          currentBuild()
        }
      }
      mailer(mailRecipients, true, true)
    }
  }
}

// generate Robot -3-robot Job
def createRobotJob(def projectGitSshUrlToRepo, def robotJobName, def gitProjectName, def mailRecipients) {
  job(robotJobName) {
    logRotator {
      daysToKeep(-1)
      numToKeep(10)
    }
    label("ci-robot")
    multiscm {
      git {
        remote {
          url(projectGitSshUrlToRepo)
          credentials(gitCredentials)
        }
        createTag(false)
        branch("master")
        relativeTargetDir(gitProjectName)
      }
      git {
        remote {
          url('git@git:test/robot-test-framework.git')
          credentials(gitCredentials)
        }
        createTag(false)
        branch("master")
        relativeTargetDir("robot-test-framework")
      }
    }
    wrappers {
      preBuildCleanup()
    }
    steps {
      ant() {
        buildFile "/src/robot/robot.xml"
        antInstallation antVersion
      }
    }
    publishers {
      chucknorris()
      publishRobotFrameworkReports {
        passThreshold(100.0)
        unstableThreshold(75.0)
        onlyCritical(false)
        outputPath("/src/robot/log")
        reportFileName('report.html')
        logFileName('log.html')
        outputFileName('output.xml')
        disableArchiveOutput(false)
        otherFiles('*.jpg', '*.png')
      }
      mailer(mailRecipients, true, true)
    }
  }
}

// create list view
def createListView(def jobViewName, def jobDescription, def regularExpression) {
  println "createListView ${jobViewName} with ${jobDescription} and ${regularExpression}"
  listView(jobViewName) {
    description(jobDescription)
    filterBuildQueue()
    filterExecutors()
    jobs {
      regex(regularExpression)
    }
    columns {
      status()
      buildButton()
      weather()
      name()
      lastSuccess()
      lastFailure()
      lastDuration()
    }
  }
}

// create nested view
def createNestedDepartmentView(def viewName) {
  println "createNestedDepartmentView for ${viewName}"
  nestedView(viewName) {
     description('Automatically generated department groups')
     columns {
        weather()
     }
     views {
        departments.each {
           def department = it
           println "Create build pipeline subview for ${department}"
           view("${department}", type: ListView) {
              description("All Jobs for department ${department}")
              filterBuildQueue()
              filterExecutors()
              jobs {
                regex("${department}-.*")
              }
              columns {
                status()
                buildButton()
                weather()
                name()
                lastSuccess()
                lastFailure()
                lastDuration()
              }
           }
        }
        view("NOGROUP", type: ListView) {
          description("All Jobs that are not assigned to a department")
          filterBuildQueue()
          filterExecutors()
          jobs {
            regex("NOGROUP-.*")
          }
          columns {
            status()
            buildButton()
            weather()
            name()
            lastSuccess()
            lastFailure()
            lastDuration()
          }
        }
     }
  }
}


/////////////////////////////////////////////////////////
// UTILITY METHODS
/////////////////////////////////////////////////////////

// Get project details from Configuration Management Database
// using JDBC and the Git Project Name.
def getProjectDetailsFromConfigMgtDb(def gitProjectName) {

  def gitProjectNameQuery = gitProjectName.toUpperCase()
  def dbSchema = "configdb"
  def dbServer = "dbserver"
  def dbUser = 'dbuser'
  def dbPassword = 'dbpassword'

  // Important: Oracle Driver needs to be available in the classpath
  def dbDriver = 'oracle.jdbc.driver.OracleDriver'
  def dbUrl = 'jdbc:oracle:thin:@' + dbServer + ':1521:' + dbSchema
  sql = Sql.newInstance( dbUrl, dbUser, dbPassword, dbDriver )

  // search project in database
  def result = sql.firstRow("SELECT * FROM projects WHERE UPPER(project_id) LIKE ${gitProjectNameQuery}")

  def isJavaProject=false
  def projectDetails = new LinkedHashMap();
  if ( result != null ) {
    projectDetails.isJavaProject=true
    projectDetails.webContextRoot=result.webContextRoot
    projectDetails.mailRecipients=result.mailRecipients
    projectDetails.isAnt=result.isAnt
    projectDetails.isMaven=result.isMaven
    projectDetails.isLibrary=result.isLibrary
    projectDetails.isBatch=result.isBatch
    projectDetails.isWebApp=result.isWebApp
    projectDetails.isArchetype=result.isArchetype
    projectDetails.javaVersion = result.javaVersion
  }
  projectDetails
}

// Reads Git Project details via GitLab REST API using Git projectId
def getGitProjectDetails(def projectId) {

  def projectDetailsApi = new URL("${gitUrl}/projects/${projectId}?private_token=${gitPrivateToken}")
  def projectDetails = new groovy.json.JsonSlurper().parse(projectDetailsApi.newReader())

  def projectTags = projectDetails.tag_list
  def defaultBranch = projectDetails.default_branch

  // determines the Git projects assigned department
  // department name is stored in Git Repository Tag.
  // defaults to 'NOGROUP'
  def validProjectGroup = "NOGROUP"
  departments.each {
    if ( projectTags != null && projectTags.contains(it) ) {
      validProjectGroup = it
    }
  }

  // checks whether the git project is still active or already archived
  def isActive = true
  if( projectDetails.archived ) {
    isActive = false
  }

  println "-> Get project details from ${gitUrl}/projects/${projectId}?private_token=${gitPrivateToken}"
  println "-> validProjectGroup=${validProjectGroup}"
  println "-> defaultBranch=${defaultBranch}"
  println "-> isActive=${isActive}"

  def details = new LinkedHashMap();
  details.validProjectGruppe=validProjectGroup
  details.defaultBranch=defaultBranch
  details.isActive=isActive
  details
}
