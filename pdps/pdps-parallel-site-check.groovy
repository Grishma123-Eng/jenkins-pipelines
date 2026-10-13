library changelog: false, identifier: "lib@ps-site-check", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/grishma123-eng/jenkins-pipelines.git'
])

// Site check needs versions with package build number (8.4.11-11.1); append .1 when only 8.4.11-11 is given.
// Returns null when the version has no percona release part (8.4.11) and full version can't be derived.
def siteCheckFullVersion(version) {
    if (version ==~ /^\d+\.\d+\.\d+-\d+\.\d+$/) {
        return version
    }
    if (version ==~ /^\d+\.\d+\.\d+-\d+$/) {
        return "${version}.1"
    }
    return null
}

pipeline {
  agent {
      label 'min-bookworm-x64'
  }
  environment {
      PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
      MOLECULE_DIR = "molecule/pdmysql/${SCENARIO}";

  }
  parameters {
        choice(
            name: 'REPO',
            description: 'Repo for testing',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        string(
            defaultValue: '8.0.33-25',
            description: 'Percona Server version for test. Possible values are with and without percona release and build: 8.0.32, 8.0.32-24 OR 8.0.32-24.2',
            name: 'VERSION'
        )
        string(
            defaultValue: '',
            description: 'Percona Server revision for test. Empty by default (not checked).',
            name: 'PS_REVISION'
        )
        string(
            defaultValue: '2.5.1',
            description: 'Proxysql version for test',
            name: 'PROXYSQL_VERSION'
        )
        string(
            defaultValue: '8.0.33-27',
            description: 'PXB version for test. Possible values are with and without percona release and build: 8.0.32, 8.0.32-25 OR 8.0.32-25.1',
            name: 'PXB_VERSION'
        )
        string(
            defaultValue: '3.5.3',
            description: 'Percona toolkit version for test',
            name: 'PT_VERSION'
        )
        string(
            defaultValue: '3.2.6-9',
            description: 'Percona orchestrator version for test',
            name: 'ORCHESTRATOR_VERSION'
        )
        string(
            defaultValue: '',
            description: 'Orchestrator revision for version from https://github.com/percona/orchestrator . Empty by default (not checked).',
            name: 'ORCHESTRATOR_REVISION'
        )
        choice(
            name: 'SCENARIO',
            description: 'PDMYSQL scenario for test',
            choices: pdpsScenarios()
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
        string(
            defaultValue: 'master',
            description: 'Tests will be run from branch of  https://github.com/percona/orchestrator',
            name: 'ORCHESTRATOR_TESTS_VERSION'
        )
        booleanParam(
            name: 'MAJOR_REPO',
            description: "Enable to use major (pdps-8.0) repo instead of pdps-8.0.XX"
        )
  }
  options {
          withCredentials(moleculePdpsJenkinsCreds())
          disableConcurrentBuilds()
  }
    stages {
        stage('Set build name'){
          steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.SCENARIO}"
                    currentBuild.description = "${env.VERSION}-${env.REPO}-${env.TESTING_BRANCH}-${env.MAJOR_REPO}"
                }
            }
        }
        stage ('Prepare') {
          steps {
                script {
                   deleteDir()
                   installMoleculeBookworm_pdps()
             }
           }
        }
        stage('Check version param and checkout') {
            steps {
                checkOrchVersionParam()
                git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
            }
        }
        stage('Test') {
          steps {
                script {
                    moleculeParallelTestPDPS(pdpsOperatingSystems(), env.MOLECULE_DIR)
                }
            }
         }
        stage('Run pdps-site-check') {
            when {
                // site check is run only for the main (release) repo and is skipped for PS 8.0 packages
                expression { params.REPO == 'release' && !params.VERSION.startsWith('8.0.') }
            }
            steps {
                script {
                    def psVerFull = siteCheckFullVersion(params.VERSION)
                    def pxbVerFull = siteCheckFullVersion(params.PXB_VERSION)
                    if (!psVerFull || !pxbVerFull) {
                        unstable("Skipping pdps-site-check: VERSION (${params.VERSION}) and PXB_VERSION (${params.PXB_VERSION}) must include percona release, e.g. 8.4.11-11 or 8.4.11-11.1")
                        return
                    }
                    echo "Triggering pdps-site-check with PS_VER_FULL=${psVerFull} PXB_VER_FULL=${pxbVerFull}"
                    build job: 'pdps-site-check', parameters: [
                        string(name: 'PS_VER_FULL', value: psVerFull),
                        string(name: 'PXB_VER_FULL', value: pxbVerFull),
                        string(name: 'ORCH_VER_FULL', value: params.ORCHESTRATOR_VERSION),
                        string(name: 'PT_VER', value: params.PT_VERSION),
                        string(name: 'PROXYSQL_VER', value: params.PROXYSQL_VERSION),
                        string(name: 'TESTING_BRANCH', value: params.TESTING_BRANCH),
                        string(name: 'TESTING_GIT_ACCOUNT', value: params.TESTING_GIT_ACCOUNT)
                    ]
                }
            }
        }
  }
    post {
        always {
          script {
              moleculeParallelPostDestroyPDPS(pdpsOperatingSystems(), env.MOLECULE_DIR)
         }
      }
   }
}
