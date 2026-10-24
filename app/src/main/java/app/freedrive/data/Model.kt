package app.freedrive.data

/**
 * The whole provider market, reduced to three adapter shapes.
 *
 * Dropping OAuth is what makes this small: anything that hands you a key is either
 * WebDAV (basic auth), S3 (access key + secret, SigV4), or a bespoke login-token REST
 * API. Anything that fails that test is not in the catalog at all.
 */
enum class AdapterKind { S3, WEBDAV, PCLOUD, TELEGRAM }

data class Field(
    val key: String,
    val label: String,
    val hint: String = "",
    val secret: Boolean = false,
    val prefill: String = "",
    val mono: Boolean = true,
    val optional: Boolean = false,
)

data class ProviderSpec(
    val id: String,
    val name: String,
    val freeGb: Int,
    val kind: AdapterKind,
    val tagline: String,
    /** Shown expanded above the form. Keep it to what a person needs to click. */
    val steps: String,
    val fields: List<Field>,
    val helpUrl: String,
    val caution: String? = null,
    /** Per-file ceiling the provider enforces. 0 means nothing beyond its own quota. */
    val maxFileMb: Int = 0,
    /** True when `freeGb` is our estimate rather than a number the provider actually stops at. */
    val appReservedQuota: Boolean = false,
)

private val s3Common = listOf(
    Field("endpoint", "Endpoint", "https://…", prefill = ""),
    Field("region", "Region", "us-east-1"),
    Field("bucket", "Bucket name", "must already exist"),
    Field("accessKey", "Access key ID"),
    Field("secretKey", "Secret access key", secret = true),
)

object Catalog {

    val all: List<ProviderSpec> = listOf(

        ProviderSpec(
            id = "koofr",
            name = "Koofr",
            freeGb = 10,
            kind = AdapterKind.WEBDAV,
            tagline = "10 GB · WebDAV · most reliable of the lot",
            steps = "Koofr → Settings → Password & security → App passwords → create one. " +
                "Use your account e-mail as the username and the app password here. " +
                "Do not use your login password.",
            helpUrl = "https://koofr.eu/help/koofr_with_webdav/",
            fields = listOf(
                Field("baseUrl", "WebDAV URL", prefill = "https://app.koofr.net/dav/Koofr"),
                Field("username", "Account e-mail", "you@example.com", mono = false),
                Field("password", "App password", "generated in Koofr settings", secret = true),
            ),
        ),

        ProviderSpec(
            id = "b2",
            name = "Backblaze B2",
            freeGb = 10,
            kind = AdapterKind.S3,
            tagline = "10 GB · S3 API · no card",
            steps = "Create a bucket (private), then Application Keys → Add a new application key. " +
                "Copy the keyID and applicationKey immediately — the secret is shown once.",
            helpUrl = "https://www.backblaze.com/docs/cloud-storage-use-the-aws-cli-with-backblaze-b2",
            fields = s3Common.map {
                when (it.key) {
                    "endpoint" -> it.copy(prefill = "https://s3.us-west-004.backblazeb2.com")
                    "region" -> it.copy(prefill = "us-west-004", hint = "match the endpoint, e.g. us-west-004")
                    else -> it
                }
            },
        ),

        ProviderSpec(
            id = "idrive",
            name = "IDrive e2",
            freeGb = 10,
            kind = AdapterKind.S3,
            tagline = "10 GB · S3 API · no card for the free tier",
            steps = "Sign in to IDrive e2 → Access Keys → create one. The endpoint is shown on the " +
                "bucket page and looks like https://xxxx.xx.idrivee2-XX.com. Use the region printed next to it.",
            helpUrl = "https://www.idrive.com/e2/",
            fields = s3Common.map {
                when (it.key) {
                    "endpoint" -> it.copy(prefill = "https://<your-endpoint>.idrivee2-XX.com")
                    "region" -> it.copy(prefill = "us-east-1")
                    else -> it
                }
            },
        ),

        ProviderSpec(
            id = "r2",
            name = "Cloudflare R2",
            freeGb = 10,
            kind = AdapterKind.S3,
            tagline = "10 GB-month · S3 API · card required",
            steps = "R2 → Create bucket → Manage R2 API Tokens → Create token with Object Read & Write. " +
                "The endpoint is https://<account-id>.r2.cloudflarestorage.com and the region is literally auto.",
            helpUrl = "https://developers.cloudflare.com/r2/api/s3/api/",
            caution = "Cloudflare asks for a payment method even on the free tier.",
            fields = s3Common.map {
                when (it.key) {
                    "endpoint" -> it.copy(prefill = "https://<account-id>.r2.cloudflarestorage.com")
                    "region" -> it.copy(prefill = "auto")
                    else -> it
                }
            },
        ),

        ProviderSpec(
            id = "oci",
            name = "Oracle Cloud",
            freeGb = 20,
            kind = AdapterKind.S3,
            tagline = "20 GB · S3-compatible · biggest single free tier",
            steps = "Object Storage → Bucket (Standard) → Customer Secret Keys → Generate Secret Key. " +
                "Endpoint: https://<namespace>.compat.objectstorage.<region>.oraclecloud.com. " +
                "Credentials are slow to propagate — if the first check fails, retry in a few minutes.",
            helpUrl = "https://docs.oracle.com/en-us/iaas/Content/Object/Tasks/s3compatibleapi.htm",
            caution = "Oracle's console is genuinely hostile. Budget ten minutes and one wrong click.",
            fields = s3Common.map {
                when (it.key) {
                    "endpoint" -> it.copy(prefill = "https://<namespace>.compat.objectstorage.<region>.oraclecloud.com")
                    "region" -> it.copy(prefill = "ap-singapore-1")
                    else -> it
                }
            },
        ),

        ProviderSpec(
            id = "pcloud",
            name = "pCloud",
            freeGb = 10,
            kind = AdapterKind.PCLOUD,
            tagline = "10 GB · token API · e-mail + password",
            steps = "Nothing to create. Enter the e-mail and password you use on pCloud; the app trades " +
                "them for a token once and stores the token, not the password. " +
                "Use api.pcloud.com for EU accounts, eapi.pcloud.com for US accounts.",
            helpUrl = "https://docs.pcloud.com/",
            caution = "This provider takes a real password. If that is not acceptable, skip it — " +
                "it is 10 GB of the 80 GB that work in this build.",
            fields = listOf(
                Field("host", "API host", prefill = "api.pcloud.com", mono = true),
                Field("username", "E-mail", "you@example.com", mono = false),
                Field("password", "Password", secret = true),
            ),
        ),

        ProviderSpec(
            id = "webdav",
            name = "Any other WebDAV",
            freeGb = 0,
            kind = AdapterKind.WEBDAV,
            tagline = "Bring your own URL · Box, Nextcloud, ownCloud, HiDrive…",
            steps = "Point at the WebDAV root you want to use. FreeDrive creates a " +
                "freedrive/ folder inside it and never touches anything else.",
            helpUrl = "https://en.wikipedia.org/wiki/WebDAV",
            fields = listOf(
                Field("baseUrl", "WebDAV URL", "https://example.com/remote.php/dav/files/me"),
                Field("username", "Username", mono = false),
                Field("password", "Password / app password", secret = true),
            ),
        ),

        ProviderSpec(
            id = "telegram",
            name = "Telegram",
            // Telegram publishes no storage quota. This is the share the pool reserves for it.
            freeGb = 10,
            kind = AdapterKind.TELEGRAM,
            maxFileMb = 20,
            appReservedQuota = true,
            tagline = "20 MB per file · chat storage · no published quota",
            steps = "Two values: a bot token from @BotFather and your numeric chat id. " +
                "The step-by-step guide below walks through both in about two minutes.",
            helpUrl = "https://core.telegram.org/bots/tutorial",
            caution = "This is chat storage, not a drive: every image becomes an attachment in a Telegram chat, " +
                "and clearing that chat deletes it. Files are capped at 20 MB because that is all a bot " +
                "can download back. Anyone holding the bot token " +
                "can read and write that chat, so make a throwaway bot and never share the token.",
            fields = listOf(
                Field("token", "Bot token", "123456789:AA…", secret = true),
                Field("chat", "Chat ID", "your numeric id, e.g. 81234567"),
            ),
        ),
    )

    fun byId(id: String): ProviderSpec? = all.firstOrNull { it.id == id }

    /** 80 GB across the catalog, counting Telegram's reserved share rather than a real quota. */
    val workingGb: Int = all.sumOf { it.freeGb }
}
