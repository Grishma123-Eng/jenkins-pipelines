def call(SLACKNOTIFY, JOB_NAME, BRANCH, BUILD_URL, FIPSMODE) {
    def prefix = (fipsMode == 'YES') ? "PRO -> " : ""

    slackNotify(
        slackChannel,
        "#00FF00",
        "[${JOB_NAME}]: ${prefix}build finished successfully for ${BRANCH} - [${BUILD_URL}]"
    )
}