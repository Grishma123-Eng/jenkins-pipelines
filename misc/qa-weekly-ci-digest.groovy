// QA weekly CI digest: package testing, tarball and docker jobs on ps80 / pxc / pxb / ps57.
// Code and job lists: misc/qa-weekly-ci-digest/ (see README.md there).
//
// Reads the last 7 days of builds from Jenkins (read-only) and archives:
//   out/slack.txt   - the message as plain text
//   out/slack.json  - the Slack Block Kit message
//   out/report.md   - full report: every job that ran, with causes
//   out/facts.json  - all computed numbers
// It posts to Slack ONLY when SLACK_CHANNEL is set AND DRY_RUN is unticked.
//
// TEMPORARY: parameters are NOT declared here on purpose. They are defined in the job's
// Configure page ("This project is parameterized"), where the 4 API tokens are saved as
// Password Parameter default values, so the Monday run works without anyone typing them.
// Job parameters expected: SLACK_CHANNEL (string), DRY_RUN (boolean), JENKINS_USER (string),
// TOKEN_PS80, TOKEN_PXC, TOKEN_PXB, TOKEN_PS57 (password).
// Once shared credentials exist on ps80, switch back to withCredentials(...).

pipeline {
    agent {
        label 'docker'
    }
    options {
        timeout(time: 60, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
    }
    environment {
        DIGEST_DIR = 'misc/qa-weekly-ci-digest'
    }
    stages {
        stage('Build digest') {
            steps {
                dir("${DIGEST_DIR}") {
                    sh '''
                        set +x   # never echo the tokens into the console log
                        for t in TOKEN_PS80 TOKEN_PXC TOKEN_PXB TOKEN_PS57; do
                            eval v=\\"\\$$t\\"
                            if [ -z "$v" ]; then
                                echo "ERROR: $t is empty. Add it as a Password Parameter (with the token as default) in Configure."
                                exit 1
                            fi
                        done
                        rm -rf out
                        docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp \
                          -e JENKINS_API_USER_PS80="$JENKINS_USER" -e JENKINS_API_TOKEN_PS80="$TOKEN_PS80" \
                          -e JENKINS_API_USER_PXC="$JENKINS_USER"  -e JENKINS_API_TOKEN_PXC="$TOKEN_PXC" \
                          -e JENKINS_API_USER_PXB="$JENKINS_USER"  -e JENKINS_API_TOKEN_PXB="$TOKEN_PXB" \
                          -e JENKINS_API_USER_PS57="$JENKINS_USER" -e JENKINS_API_TOKEN_PS57="$TOKEN_PS57" \
                          -e BUILD_URL -e JOB_NAME -e BUILD_NUMBER \
                          -v "$PWD:/w" -w /w python:3.13-slim sh -c '
                            pip install --quiet --user -r requirements.txt &&
                            python digest.py --out out'
                    '''
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