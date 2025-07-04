library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])



def operatingsystems() {
    return ['ubuntu-noble','ubuntu-jammy','debian-11','debian-12','oracle-8','oracle-9']
}


pipeline {
  agent {
    label 'min-bookworm-x64'
  }
  environment {
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
    MOLECULE_DIR = "molecule/proxysql-tarball/";
    PROXYSQL_VERSION = "${params.Proxysql_version}"
    WSREP_VERSION = "${params.WSREP_VERSION}"
  }
  parameters {
    string(
      name: 'Proxysql_version', 
      defaultValue: '8.0.36-28', 
      description: 'proxysql full version'
    )
    string(
      defaultValue: 'master',
      description: 'Branch for package-testing repository',
      name: 'TESTING_BRANCH'
    )
    string(
      defaultValue: 'Percona-QA',
      description: 'Git account for package-testing repository',
      name: 'TESTING_GIT_ACCOUNT'
    )
  }
  options {
    withCredentials(moleculepxcJenkinsCreds())
    disableConcurrentBuilds()
  }
  stages {
    stage('Set build name'){
      steps {
        script {
          currentBuild.displayName = "${env.BUILD_NUMBER}-${env.Proxysql_version}"
          currentBuild.description = "${env.REVISION}-${env.TESTING_BRANCH}-${env.TESTING_GIT_ACCOUNT}"
        }
      }
    }
    stage('Checkout') {
      steps {
        deleteDir()
        git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
      }
    }
    stage ('Prepare') {
      steps {
        script {
          installMoleculeBookworm()
        }
      }
    }

    stage('Run tarball molecule') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
          script {
            moleculeParallelTest(operatingsystems(), env.MOLECULE_DIR)
          }
        }
      }
    }
  }
  post {
    always {
      script {
        moleculeParallelPostDestroy(operatingsystems(), env.MOLECULE_DIR)
      }
    }
  }
}