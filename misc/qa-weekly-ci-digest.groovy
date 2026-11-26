// QA weekly CI digest: package testing, tarball and docker jobs on ps80 / pxc / pxb / ps57.
// Code and job lists: misc/qa-weekly-ci-digest/ (see README.md there).
//
// Every Monday it reads the last 7 days of builds from Jenkins (read-only) and archives:
//   out/slack.txt   - the message as plain text
//   out/slack.json  - the Slack Block Kit message
//   out/report.md   - full report: every job that ran, with causes
//   out/facts.json  - all computed numbers
// It posts to Slack ONLY when SLACK_CHANNEL is set AND DRY_RUN is unticked.
//
// Jenkins API access: JNKPercona service account, one read-only token per master, stored as
// Username-with-password credentials in the qa-reports folder on ps80. The job must live in
// that folder to see them.

pipeline {
    agent {
        label 'docker'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
    }
    parameters {
        string(name: 'SLACK_CHANNEL', defaultValue: '#mysql-qa-job',
               description: 'Slack channel name or ID. Empty = build the digest, do not post.')
        booleanParam(name: 'DRY_RUN', defaultValue: false,
               description: 'Ticked = build the digest but do not post to Slack (preview only).')
    }
    environment {
        DIGEST_DIR = 'misc/qa-weekly-ci-digest'
    }
    stages {
        stage('Build digest') {
            steps {
                dir("${DIGEST_DIR}") {
                    withCredentials([
                        usernamePassword(credentialsId: 'qa-digest-jenkins-ps80',
                            usernameVariable: 'JENKINS_API_USER_PS80', passwordVariable: 'JENKINS_API_TOKEN_PS80'),
                        usernamePassword(credentialsId: 'qa-digest-jenkins-pxc',
                            usernameVariable: 'JENKINS_API_USER_PXC',  passwordVariable: 'JENKINS_API_TOKEN_PXC'),
                        usernamePassword(credentialsId: 'qa-digest-jenkins-pxb',
                            usernameVariable: 'JENKINS_API_USER_PXB',  passwordVariable: 'JENKINS_API_TOKEN_PXB'),
                        usernamePassword(credentialsId: 'qa-digest-jenkins-ps57',
                            usernameVariable: 'JENKINS_API_USER_PS57', passwordVariable: 'JENKINS_API_TOKEN_PS57'),
                    ]) {
                        sh '''
                            rm -rf out
                            docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp \
                              -e JENKINS_API_USER_PS80 -e JENKINS_API_TOKEN_PS80 \
                              -e JENKINS_API_USER_PXC  -e JENKINS_API_TOKEN_PXC \
                              -e JENKINS_API_USER_PXB  -e JENKINS_API_TOKEN_PXB \
                              -e JENKINS_API_USER_PS57 -e JENKINS_API_TOKEN_PS57 \
                              -e BUILD_URL -e JOB_NAME -e BUILD_NUMBER \
                              -v "$PWD:/w" -w /w python:3.13-slim sh -c '
                                pip install --quiet --user -r requirements.txt &&
                                python digest.py --out out'
                        '''
                    }
                }
            }
        }
        stage('Post to Slack') {
            when { expression { params.SLACK_CHANNEL?.trim() && params.DRY_RUN == false } }
            steps {
                script {
                    def msg = readJSON file: "${DIGEST_DIR}/out/slack.json"
                    slackSend(channel: params.SLACK_CHANNEL.trim(), botUser: true,
                              blocks: msg.blocks, message: msg.text)
                }
            }
        }
    }
    post {
        always {
            archiveArtifacts artifacts: "${DIGEST_DIR}/out/*", allowEmptyArchive: true
            script {
                currentBuild.description = (params.SLACK_CHANNEL?.trim() && params.DRY_RUN == false) ?
                    "posted to ${params.SLACK_CHANNEL}" : 'preview only, not posted'
            }
        }
    }
}