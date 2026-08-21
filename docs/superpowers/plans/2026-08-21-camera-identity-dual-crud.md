# Tách định danh camera — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Camera CRUD được từ Web UI LAN và từ dashboard cloud mà không bên nào hiển thị thông tin của camera khác, và mọi cơ chế nội bộ của NVR (Frigate, go2rtc, Box, event, playback) vẫn đúng.

**Architecture:** Tách `channel_id` khỏi vai khoá định danh nội bộ. `uid` (UUIDv4) bất biến sinh ra `slug`; `channel_id` chỉ còn nghĩa "số đã phát ra ngoài" và được quản bởi một cổng duy nhất `ChannelAllocator` cùng bảng `channel_claims`. Camera `owner='cloud'` bị khoá sửa/xoá từ Web UI và là camera duy nhất cloud phân giải được.

**Tech Stack:** Kotlin + Ktor 3.0.1, Exposed + SQLite, kotlin-test-junit, React 19 + Vite 7 + Tailwind 4.

**Spec:** `docs/superpowers/specs/2026-08-21-camera-identity-dual-crud-design.md`

## Global Constraints

- Log qua `AppLogger` với `Module`/`componentId`/`eventType` theo `CLAUDE.md` §0.1. Không `LoggerFactory.getLogger` mới. Không `catch { }` trống.
- Mọi đường ghi `config.yml` phải đi qua `FrigateConfigGuard`. Không code nào ngoài `CameraService` được ghi `config.yml`/go2rtc/DB camera (constitution P1).
- Không code nào ngoài `ChannelAllocator` được quyết định một `channel_id`.
- `slug` của camera đã tồn tại **không đổi**. Luật `cam_<uid8>` chỉ áp cho camera tạo sau migration.
- `channel_id` không nullable ở tầng logic; camera local vẫn có số (Box + camera Bkav cần).
- Sau mỗi thay đổi: append changelog vào `/home/toan/toanDoc/Documents/1CodeBkav/ChangeLog/NVR.md` theo khuôn `CLAUDE.md` §0.2.
- Test chạy bằng `cd backend && ./gradlew test`. Build kiểm tra bằng `cd backend && ./gradlew installDist`.
- Ngưỡng giải phóng camera: khoá `app_settings` tên `cloud_free_after_days`, mặc định `7`.

---

### Task 1: Dựng harness test cho backend

`backend/src/test` chưa tồn tại — repo hiện không có unit test Kotlin nào cho backend. Task này dựng harness và chứng minh nó chạy; mọi task sau dựa vào nó.

**Files:**
- Create: `backend/src/test/kotlin/vn/aiview/nvr/TestDb.kt`
- Create: `backend/src/test/kotlin/vn/aiview/nvr/TestDbSmokeTest.kt`
- Modify: `backend/build.gradle.kts` (thêm `useJUnitPlatform` nếu chưa có, và `sqlite-jdbc` cho testRuntime nếu chưa có ở configuration đó)

**Interfaces:**
- Consumes: không
- Produces: `TestDb.withCleanDb(block: () -> Unit)` — chạy `block` trong một transaction trên DB SQLite in-memory đã tạo sẵn `Cameras`, `ChannelClaims`, `AppSettings`, `AIEvents`. Mỗi lần gọi là một DB mới, không rớt trạng thái sang test khác.

- [ ] **Step 1: Viết harness**

`backend/src/test/kotlin/vn/aiview/nvr/TestDb.kt`:

```kotlin
package vn.aiview.nvr

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import vn.aiview.nvr.models.AIEvents
import vn.aiview.nvr.models.AppSettings
import vn.aiview.nvr.models.Cameras
import java.util.concurrent.atomic.AtomicInteger

/**
 * DB SQLite in-memory cho unit test. Mỗi lần gọi [withCleanDb] là một database MỚI
 * (tên khác nhau) — không dùng chung `:memory:` vì Exposed giữ connection pool và hai
 * test sẽ thấy dữ liệu của nhau, làm test xanh/đỏ theo thứ tự chạy.
 */
object TestDb {
    private val seq = AtomicInteger(0)

    fun withCleanDb(block: () -> Unit) {
        val name = "test_${seq.incrementAndGet()}"
        val db = Database.connect("jdbc:sqlite:file:$name?mode=memory&cache=shared", "org.sqlite.JDBC")
        transaction(db) {
            SchemaUtils.create(Cameras, AppSettings, AIEvents)
            block()
        }
    }
}
```

- [ ] **Step 2: Viết smoke test**

`backend/src/test/kotlin/vn/aiview/nvr/TestDbSmokeTest.kt`:

```kotlin
package vn.aiview.nvr

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import vn.aiview.nvr.models.Cameras
import kotlin.test.Test
import kotlin.test.assertEquals

class TestDbSmokeTest {
    @Test
    fun `bang cameras dung duoc va moi test la DB rieng`() = TestDb.withCleanDb {
        Cameras.insert {
            it[Cameras.slug] = "cam_x"
            it[Cameras.displayName] = "X"
            it[Cameras.channelId] = "1"
        }
        assertEquals(1, Cameras.selectAll().count())
    }

    @Test
    fun `test thu hai khong thay du lieu cua test thu nhat`() = TestDb.withCleanDb {
        assertEquals(0, Cameras.selectAll().count())
    }
}
```

- [ ] **Step 3: Chạy test, kỳ vọng FAIL vì chưa cấu hình**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.TestDbSmokeTest"`
Expected: FAIL — hoặc "no tests found" (chưa bật JUnit platform), hoặc `ClassNotFoundException: org.sqlite.JDBC`.

- [ ] **Step 4: Cấu hình gradle cho tới khi test xanh**

Trong `backend/build.gradle.kts`, thêm ở cuối file nếu chưa có:

```kotlin
tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}
```

Trong khối `dependencies`, cạnh hai dòng `testImplementation` đang có, thêm:

```kotlin
    testImplementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    testImplementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    testImplementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    testRuntimeOnly("org.xerial:sqlite-jdbc:$sqlite_version")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
```

Nếu `$sqlite_version`/`$exposed_version` không có trong `gradle.properties`, dùng đúng giá trị đang dùng ở khối `implementation` của file này.

- [ ] **Step 5: Chạy lại, kỳ vọng PASS**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.TestDbSmokeTest"`
Expected: PASS, 2 test.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test backend/build.gradle.kts
git commit -m "Test(backend): dựng harness unit test đầu tiên cho backend (SQLite in-memory)"
```

---

### Task 2: Schema — `uid`, `owner`, bảng `channel_claims`

**Files:**
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/models/Schema.kt`
- Create: `backend/src/main/kotlin/vn/aiview/nvr/models/ChannelClaims.kt`
- Modify: `backend/src/test/kotlin/vn/aiview/nvr/TestDb.kt` (thêm `ChannelClaims` vào `SchemaUtils.create`)
- Test: `backend/src/test/kotlin/vn/aiview/nvr/models/ChannelClaimsSchemaTest.kt`

**Interfaces:**
- Consumes: `TestDb.withCleanDb` từ Task 1
- Produces:
  - `Cameras.uid: Column<String>` — varchar 36, default `""` (migration Task 3 sẽ lấp)
  - `Cameras.owner: Column<String>` — varchar 10, default `"local"`
  - `object ChannelClaims : Table("channel_claims")` với `channelId` (PK, varchar 50), `issuedTo` (varchar 10), `state` (varchar 10), `claimedAt` (varchar 40, ISO-8601), `lastSeen` (varchar 40)
  - `enum class IssuedTo { CLOUD, BOX, BKAV }` với `val wire: String` = lowercase
  - `enum class ClaimState { ACTIVE, ORPHAN, RELEASED }` với `val wire: String` = lowercase

- [ ] **Step 1: Viết test cho schema**

`backend/src/test/kotlin/vn/aiview/nvr/models/ChannelClaimsSchemaTest.kt`:

```kotlin
package vn.aiview.nvr.models

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import vn.aiview.nvr.TestDb
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelClaimsSchemaTest {
    @Test
    fun `ghi va doc mot claim`() = TestDb.withCleanDb {
        ChannelClaims.insert {
            it[ChannelClaims.channelId] = "1"
            it[ChannelClaims.issuedTo] = IssuedTo.CLOUD.wire
            it[ChannelClaims.state] = ClaimState.ACTIVE.wire
            it[ChannelClaims.claimedAt] = "2026-08-21T00:00:00Z"
            it[ChannelClaims.lastSeen] = "2026-08-21T00:00:00Z"
        }
        val row = ChannelClaims.selectAll().single()
        assertEquals("1", row[ChannelClaims.channelId])
        assertEquals("cloud", row[ChannelClaims.issuedTo])
        assertEquals("active", row[ChannelClaims.state])
    }

    @Test
    fun `camera co uid va owner mac dinh local`() = TestDb.withCleanDb {
        Cameras.insert {
            it[Cameras.slug] = "cam_a"
            it[Cameras.displayName] = "A"
            it[Cameras.channelId] = "16"
            it[Cameras.uid] = "11111111-2222-3333-4444-555555555555"
        }
        val row = Cameras.selectAll().single()
        assertEquals("local", row[Cameras.owner])
        assertEquals("11111111-2222-3333-4444-555555555555", row[Cameras.uid])
    }
}
```

- [ ] **Step 2: Chạy test, kỳ vọng FAIL**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.models.ChannelClaimsSchemaTest"`
Expected: FAIL — compile error, `ChannelClaims`/`IssuedTo`/`Cameras.uid` chưa tồn tại.

- [ ] **Step 3: Tạo bảng và enum**

`backend/src/main/kotlin/vn/aiview/nvr/models/ChannelClaims.kt`:

```kotlin
package vn.aiview.nvr.models

import org.jetbrains.exposed.sql.Table

/**
 * Ký ức của NVR về những số kênh ĐÃ PHÁT RA NGOÀI máy.
 *
 * Vì sao cần: cloud, AiView Box và camera Bkav đều tự nhớ `channel_id` ở phía họ, và NVR
 * không xoá hộ được. Đã đo 21-08-2026: xoá camera ở NVR thì dashboard cloud chuyển kênh đó
 * sang offline nhưng GIỮ NGUYÊN bản ghi. Nên "số 1 trống" dưới mắt bảng `cameras` KHÔNG có
 * nghĩa là số 1 trống dưới mắt cloud — cấp lại nó cho camera khác là đúng sự cố 192.168.2.4.
 *
 * Claim chỉ ghi khi số THẬT SỰ được giao cho bên ngoài, không ghi lúc cấp. Nếu ghi lúc cấp
 * thì 16 số cạn trong một buổi test và luật này là thứ đầu tiên bị tắt đi.
 */
object ChannelClaims : Table("channel_claims") {
    val channelId = varchar("channel_id", 50)
    val issuedTo = varchar("issued_to", 10)
    val state = varchar("state", 10)
    /** ISO-8601. Lưu chuỗi thay vì datetime để đọc log/DB bằng mắt không phải đổi timezone. */
    val claimedAt = varchar("claimed_at", 40)
    val lastSeen = varchar("last_seen", 40)

    override val primaryKey = PrimaryKey(channelId)
}

enum class IssuedTo(val wire: String) {
    CLOUD("cloud"), BOX("box"), BKAV("bkav");

    companion object {
        fun parse(s: String): IssuedTo? = entries.firstOrNull { it.wire == s }
    }
}

enum class ClaimState(val wire: String) {
    /** Đang có camera giữ số này. */
    ACTIVE("active"),
    /** Không còn camera, nhưng bên ngoài có thể vẫn nhớ. KHÔNG được cấp lại. */
    ORPHAN("orphan"),
    /** Người vận hành đã xác nhận dọn xong ở bên ngoài → cấp lại được. */
    RELEASED("released");

    companion object {
        fun parse(s: String): ClaimState? = entries.firstOrNull { it.wire == s }
    }
}
```

- [ ] **Step 4: Thêm hai cột vào `Cameras`**

Trong `backend/src/main/kotlin/vn/aiview/nvr/models/Schema.kt`, ngay sau dòng `val slug = varchar("slug", 255)`:

```kotlin
    /**
     * Định danh nội bộ BẤT BIẾN, không bao giờ cấp lại. `slug` của camera mới sinh từ đây
     * (`cam_<8 hex đầu>`) thay vì từ `channel_id` — vì `channel_id` bị tái sử dụng, và slug
     * là tên camera trong config.yml của Frigate, tức thư mục ghi hình và khoá của ai_events.
     * Slug tái sử dụng = playback hiện hình camera cũ dán nhãn camera mới.
     *
     * Default "" để Exposed ALTER TABLE ADD COLUMN được trên máy đã có dữ liệu; migration
     * `backfillCameraIdentity()` lấp giá trị thật ngay sau đó.
     */
    val uid = varchar("uid", 36).default("")

    /** 'cloud' = do dashboard cloud tạo, Web UI không được sửa/xoá. 'local' = do Web UI tạo. */
    val owner = varchar("owner", 10).default("local")
```

- [ ] **Step 5: Thêm `ChannelClaims` vào harness test**

Trong `backend/src/test/kotlin/vn/aiview/nvr/TestDb.kt`, đổi dòng `SchemaUtils.create(...)` thành:

```kotlin
            SchemaUtils.create(Cameras, AppSettings, AIEvents, vn.aiview.nvr.models.ChannelClaims)
```

- [ ] **Step 6: Chạy test, kỳ vọng PASS**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.models.ChannelClaimsSchemaTest"`
Expected: PASS, 2 test.

- [ ] **Step 7: Đăng ký bảng mới vào Databases.kt**

Trong `backend/src/main/kotlin/vn/aiview/nvr/plugins/Databases.kt`, thêm `ChannelClaims` vào danh sách `SchemaUtils.createMissingTablesAndColumns(...)` (sau `AppSettings`), và thêm import `vn.aiview.nvr.models.ChannelClaims`.

- [ ] **Step 8: Chạy build đầy đủ**

Run: `cd backend && ./gradlew installDist`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/kotlin/vn/aiview/nvr/models backend/src/main/kotlin/vn/aiview/nvr/plugins/Databases.kt backend/src/test
git commit -m "Feat(camera-identity): thêm uid + owner cho cameras và bảng channel_claims"
```

---

### Task 3: Migration lấp `uid` và suy `owner`

**Files:**
- Create: `backend/src/main/kotlin/vn/aiview/nvr/plugins/CameraIdentityMigration.kt`
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/plugins/Databases.kt`
- Test: `backend/src/test/kotlin/vn/aiview/nvr/plugins/CameraIdentityMigrationTest.kt`

**Interfaces:**
- Consumes: `Cameras.uid`, `Cameras.owner`, `ChannelClaims`, `IssuedTo`, `ClaimState` từ Task 2
- Produces: `fun backfillCameraIdentity()` — idempotent, gọi trong `transaction` của `Databases.kt`

- [ ] **Step 1: Viết test**

`backend/src/test/kotlin/vn/aiview/nvr/plugins/CameraIdentityMigrationTest.kt`:

```kotlin
package vn.aiview.nvr.plugins

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import vn.aiview.nvr.TestDb
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.models.ChannelClaims
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraIdentityMigrationTest {

    private fun insertCam(slug: String, ch: String) = Cameras.insert {
        it[Cameras.slug] = slug
        it[Cameras.displayName] = slug
        it[Cameras.channelId] = ch
    }

    @Test
    fun `sinh uid cho camera chua co, khong doi slug`() = TestDb.withCleanDb {
        insertCam("cam_2", "2")
        backfillCameraIdentity()
        val row = Cameras.selectAll().single()
        assertEquals("cam_2", row[Cameras.slug])
        assertEquals(36, row[Cameras.uid].length)
    }

    @Test
    fun `chay hai lan khong doi uid`() = TestDb.withCleanDb {
        insertCam("cam_2", "2")
        backfillCameraIdentity()
        val first = Cameras.selectAll().single()[Cameras.uid]
        backfillCameraIdentity()
        assertEquals(first, Cameras.selectAll().single()[Cameras.uid])
    }

    @Test
    fun `owner la cloud khi co claim cloud cho so do`() = TestDb.withCleanDb {
        insertCam("cam_2", "2")
        ChannelClaims.insert {
            it[ChannelClaims.channelId] = "2"
            it[ChannelClaims.issuedTo] = "cloud"
            it[ChannelClaims.state] = "active"
            it[ChannelClaims.claimedAt] = "2026-08-21T00:00:00Z"
            it[ChannelClaims.lastSeen] = "2026-08-21T00:00:00Z"
        }
        backfillCameraIdentity()
        assertEquals("cloud", Cameras.selectAll().single()[Cameras.owner])
    }

    @Test
    fun `owner la local khi khong co claim`() = TestDb.withCleanDb {
        insertCam("cam_16", "16")
        backfillCameraIdentity()
        assertEquals("local", Cameras.selectAll().single()[Cameras.owner])
    }

    @Test
    fun `moi channel_id dang ton tai thanh claim active`() = TestDb.withCleanDb {
        insertCam("cam_2", "2")
        backfillCameraIdentity()
        val claim = ChannelClaims.selectAll().single()
        assertEquals("2", claim[ChannelClaims.channelId])
        assertEquals("active", claim[ChannelClaims.state])
    }

    @Test
    fun `khong tao claim cho so khong co camera nao`() = TestDb.withCleanDb {
        insertCam("cam_2", "2")
        backfillCameraIdentity()
        assertTrue(ChannelClaims.selectAll().none { it[ChannelClaims.channelId] == "1" })
    }
}
```

- [ ] **Step 2: Chạy test, kỳ vọng FAIL**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.plugins.CameraIdentityMigrationTest"`
Expected: FAIL — `backfillCameraIdentity` chưa tồn tại.

- [ ] **Step 3: Viết migration**

`backend/src/main/kotlin/vn/aiview/nvr/plugins/CameraIdentityMigration.kt`:

```kotlin
package vn.aiview.nvr.plugins

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.models.ChannelClaims
import vn.aiview.nvr.utils.AppLogger
import java.time.Instant
import java.util.UUID

/**
 * Nâng máy cũ lên mô hình định danh mới. Idempotent — gọi mỗi lần khởi động.
 *
 * Ba việc, và một việc CỐ TÌNH KHÔNG LÀM:
 *
 *  1. Camera chưa có `uid` → sinh UUIDv4.
 *  2. `owner`: có claim `cloud` cho `channel_id` đó thì `cloud`, không thì `local`. Cloud là
 *     phía an toàn hơn khi đoán — đoán sai thành `cloud` chỉ gây khoá oan (mở được bằng
 *     đường "tắt Kết nối Cloud"), còn đoán sai thành `local` là mở lại đúng cái lỗ đang bịt.
 *  3. Mọi `channel_id` đang tồn tại → claim `active`. KHÔNG suy diễn quá khứ từ log, vì log
 *     rotate 30 ngày và một suy diễn sai ở đây sinh ra claim ma chặn số vĩnh viễn.
 *
 *  KHÔNG đổi `slug` của camera đang có. Đổi nghĩa là UPDATE hàng nghìn dòng `ai_events`, đổi
 *  tên thư mục `media/events/<slug>/`, sửa `snapshot_path`/`clip_path` tuyệt đối, và đổi khoá
 *  camera trong config.yml — một migration rủi ro thật để đổi lấy sự nhất quán hình thức.
 *  Camera cũ không có nguy cơ lẫn playback vì slug của nó chưa từng bị cấp lại. Hệ quả: hai
 *  kiểu slug (`cam_<số>` cũ và `cam_<uid8>` mới) cùng tồn tại — đã ghi trong CLAUDE.md.
 */
fun backfillCameraIdentity() {
    val now = Instant.now().toString()

    val cloudChannels = ChannelClaims.selectAll()
        .filter { it[ChannelClaims.issuedTo] == "cloud" }
        .map { it[ChannelClaims.channelId] }
        .toSet()

    var uidFilled = 0
    var ownerSet = 0
    var claimsAdded = 0

    val rows = Cameras.selectAll().toList()
    for (row in rows) {
        val slug = row[Cameras.slug]
        val ch = row[Cameras.channelId]

        if (row[Cameras.uid].isBlank()) {
            val uid = UUID.randomUUID().toString()
            Cameras.update({ Cameras.slug eq slug }) { it[Cameras.uid] = uid }
            uidFilled++
        }

        if (ch != null && ch in cloudChannels && row[Cameras.owner] != "cloud") {
            Cameras.update({ Cameras.slug eq slug }) { it[Cameras.owner] = "cloud" }
            ownerSet++
        }

        if (ch != null && ChannelClaims.selectAll().none { it[ChannelClaims.channelId] == ch }) {
            ChannelClaims.insert {
                it[ChannelClaims.channelId] = ch
                it[ChannelClaims.issuedTo] = if (ch in cloudChannels) "cloud" else "box"
                it[ChannelClaims.state] = "active"
                it[ChannelClaims.claimedAt] = now
                it[ChannelClaims.lastSeen] = now
            }
            claimsAdded++
        }
    }

    if (uidFilled > 0 || ownerSet > 0 || claimsAdded > 0) {
        AppLogger.info(AppLogger.Module.CAMERA, "IDENTITY_MIGRATION", "BACKFILL_OK",
            "cameras=${rows.size} uid=$uidFilled owner_cloud=$ownerSet claims=$claimsAdded")
    }
}
```

> Ghi chú về `issuedTo = "box"` ở nhánh không phải cloud: camera đang tồn tại đã có thể được
> trả cho Box qua `/getprofilecamera` trước khi có cơ chế claim, và ta không biết. Chọn phía
> an toàn — coi như đã phát ra ngoài, số đó không tự do cấp lại.

- [ ] **Step 4: Chạy test, kỳ vọng PASS**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.plugins.CameraIdentityMigrationTest"`
Expected: PASS, 6 test.

- [ ] **Step 5: Móc vào khởi động**

Trong `backend/src/main/kotlin/vn/aiview/nvr/plugins/Databases.kt`, trong khối `transaction(database)`, thêm **sau** `migrateCloudCamerasIntoCameras()`:

```kotlin
        backfillCameraIdentity()
```

- [ ] **Step 6: Chạy thật trên máy dev và kiểm DB**

```bash
./run.sh --rebuild
```

Rồi:

```bash
python3 -c "
import sqlite3;c=sqlite3.connect('data/nvr.db')
for r in c.execute('select slug,channel_id,uid,owner from cameras'): print(r)
for r in c.execute('select * from channel_claims'): print(r)
"
```

Expected: mỗi camera có `uid` dài 36, `slug` không đổi, và mỗi `channel_id` có một dòng `channel_claims` state `active`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/vn/aiview/nvr/plugins backend/src/test
git commit -m "Feat(camera-identity): migration lấp uid + suy owner + dựng claim ban đầu"
```

---

### Task 4: `ChannelAllocator` — cổng duy nhất cấp số kênh

Task có giá trị test cao nhất trong plan: logic thuần, và là chỗ sự cố `192.168.2.4` xảy ra.

**Files:**
- Create: `backend/src/main/kotlin/vn/aiview/nvr/services/ChannelAllocator.kt`
- Test: `backend/src/test/kotlin/vn/aiview/nvr/services/ChannelAllocatorTest.kt`

**Interfaces:**
- Consumes: `ChannelClaims`, `IssuedTo`, `ClaimState`, `Cameras` từ Task 2
- Produces:
  - `object ChannelAllocator`
  - `class ChannelSpaceExhausted(val orphanCount: Int) : Exception` — message tiếng Việt trỏ màn dọn
  - `fun allocateLocal(): String` — số cao nhất còn tự do trong `1..16`, đếm từ 16 xuống. Ném `ChannelSpaceExhausted` nếu hết
  - `fun claim(channelId: String, issuedTo: IssuedTo)` — upsert claim state `active`, cập nhật `lastSeen`
  - `fun touch(channelId: String)` — chỉ cập nhật `lastSeen`
  - `fun orphanIfIssued(channelId: String)` — camera rời số: có claim thì → `orphan`, không có claim thì xoá dòng (số tự do lại)
  - `fun release(channelId: String)` — `orphan` → `released`
  - `fun hasExternalClaim(channelId: String): Boolean` — có claim state `active`/`orphan`
  - `fun localCeiling(): Int` — `16 - (số camera local + số claim local chưa released)`, sàn `0`

- [ ] **Step 1: Viết test**

`backend/src/test/kotlin/vn/aiview/nvr/services/ChannelAllocatorTest.kt`:

```kotlin
package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.insert
import vn.aiview.nvr.TestDb
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.models.ChannelClaims
import vn.aiview.nvr.models.IssuedTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelAllocatorTest {

    private fun cam(slug: String, ch: String, owner: String = "local") = Cameras.insert {
        it[Cameras.slug] = slug
        it[Cameras.displayName] = slug
        it[Cameras.channelId] = ch
        it[Cameras.owner] = owner
        it[Cameras.uid] = slug
    }

    @Test
    fun `cap so local lan dau la 16`() = TestDb.withCleanDb {
        assertEquals("16", ChannelAllocator.allocateLocal())
    }

    @Test
    fun `cap so local khi 16 dang dung thi ra 15`() = TestDb.withCleanDb {
        cam("cam_a", "16")
        assertEquals("15", ChannelAllocator.allocateLocal())
    }

    @Test
    fun `khong cap lai so dang orphan`() = TestDb.withCleanDb {
        cam("cam_a", "16")
        ChannelAllocator.claim("15", IssuedTo.BOX)
        ChannelAllocator.orphanIfIssued("15")
        assertEquals("14", ChannelAllocator.allocateLocal())
    }

    @Test
    fun `cap lai duoc so da released`() = TestDb.withCleanDb {
        cam("cam_a", "16")
        ChannelAllocator.claim("15", IssuedTo.BOX)
        ChannelAllocator.orphanIfIssued("15")
        ChannelAllocator.release("15")
        assertEquals("15", ChannelAllocator.allocateLocal())
    }

    @Test
    fun `so chua tung ra ngoai thi ve dung lai duoc ngay`() = TestDb.withCleanDb {
        cam("cam_a", "16")
        ChannelAllocator.orphanIfIssued("16")
        assertFalse(ChannelAllocator.hasExternalClaim("16"))
    }

    @Test
    fun `so da tra cho Box thi thanh orphan chu khong tu do`() = TestDb.withCleanDb {
        ChannelAllocator.claim("16", IssuedTo.BOX)
        ChannelAllocator.orphanIfIssued("16")
        assertTrue(ChannelAllocator.hasExternalClaim("16"))
    }

    @Test
    fun `het so thi nem loi chu khong cap lai orphan`() = TestDb.withCleanDb {
        for (n in 1..16) ChannelAllocator.claim(n.toString(), IssuedTo.CLOUD)
        val e = assertFailsWith<ChannelSpaceExhausted> { ChannelAllocator.allocateLocal() }
        assertEquals(16, e.orphanCount)
    }

    @Test
    fun `tran cua cloud tut theo so camera local`() = TestDb.withCleanDb {
        cam("cam_a", "16")
        cam("cam_b", "15")
        cam("cam_c", "14")
        assertEquals(13, ChannelAllocator.localCeiling())
    }

    @Test
    fun `camera cloud khong lam tut tran cua cloud`() = TestDb.withCleanDb {
        cam("cam_a", "1", owner = "cloud")
        assertEquals(16, ChannelAllocator.localCeiling())
    }

    @Test
    fun `claim hai lan khong tao dong trung`() = TestDb.withCleanDb {
        ChannelAllocator.claim("5", IssuedTo.CLOUD)
        ChannelAllocator.claim("5", IssuedTo.CLOUD)
        assertEquals(1, ChannelClaims.selectAll().count())
    }
}
```

- [ ] **Step 2: Chạy test, kỳ vọng FAIL**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.ChannelAllocatorTest"`
Expected: FAIL — `ChannelAllocator` chưa tồn tại.

- [ ] **Step 3: Viết `ChannelAllocator`**

`backend/src/main/kotlin/vn/aiview/nvr/services/ChannelAllocator.kt`:

```kotlin
package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import vn.aiview.nvr.cloud.DeviceConstants
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.models.ChannelClaims
import vn.aiview.nvr.models.ClaimState
import vn.aiview.nvr.models.IssuedTo
import vn.aiview.nvr.utils.AppLogger
import java.time.Instant

/**
 * Hết số kênh cấp được. Không tự cấp lại một số `orphan` — cấp lại là đúng sự cố
 * 192.168.2.4 (cloud giữ bản ghi cũ, NVR gán số cho camera khác, dashboard nói sai).
 */
class ChannelSpaceExhausted(val orphanCount: Int) : Exception(
    "Hết số kênh cấp được. Có $orphanCount số đã phát ra ngoài (cloud/Box) chưa thu hồi. " +
        "Vào Thiết lập → Số kênh đã phát ra ngoài để dọn trước khi thêm camera."
)

/**
 * CỔNG DUY NHẤT quyết định một `channel_id`. Không code nào khác được tự chọn số.
 *
 * Trước đây `CameraService.assignNextChannelIdLocked()` cấp số nhỏ nhất chưa có trong bảng
 * `cameras`. Nó chỉ biết trạng thái của chính NVR, mà cloud/Box/camera Bkav đều tự nhớ số ở
 * phía họ và NVR không xoá hộ được. Nên "trống dưới mắt NVR" ≠ "trống thật".
 *
 * Hai đầu: cloud cấp từ 1 lên (hành vi quan sát được của server), Web UI cấp từ 16 xuống.
 * Không chia cứng 8/8 — khách chỉ dùng local sẽ bị chặn ở 8 camera trên NVR 16 kênh. Thay
 * vào đó [localCeiling] khai trần tụt dần cho cloud qua `maxChannelNum`.
 */
object ChannelAllocator {

    private val MAX = DeviceConstants.NUMBER_OF_CHANNEL

    private fun now() = Instant.now().toString()

    private fun claimState(channelId: String): ClaimState? =
        ChannelClaims.selectAll()
            .firstOrNull { it[ChannelClaims.channelId] == channelId }
            ?.let { ClaimState.parse(it[ChannelClaims.state]) }

    /** Số đang có camera giữ. */
    private fun channelsInUse(): Set<String> =
        Cameras.selectAll().mapNotNull { it[Cameras.channelId] }.toSet()

    /** Số bị chặn vì đã phát ra ngoài và chưa thu hồi. */
    private fun blockedChannels(): Set<String> =
        ChannelClaims.selectAll()
            .filter { ClaimState.parse(it[ChannelClaims.state]) != ClaimState.RELEASED }
            .map { it[ChannelClaims.channelId] }
            .toSet()

    fun hasExternalClaim(channelId: String): Boolean =
        claimState(channelId).let { it == ClaimState.ACTIVE || it == ClaimState.ORPHAN }

    /**
     * Cấp số cho camera thêm từ Web UI: cao nhất còn tự do, đếm từ [MAX] xuống.
     * Đếm xuống để tránh dải mà cloud đang cấp lên — xung đột KHÔNG XẢY RA thay vì
     * phải xử lý xung đột cho êm (xử lý êm nghĩa là dời số, mà dời số thì Box giữ ma).
     */
    fun allocateLocal(): String {
        val taken = channelsInUse() + blockedChannels()
        for (n in MAX downTo 1) {
            val s = n.toString()
            if (s !in taken) {
                AppLogger.info(AppLogger.Module.CAMERA, "CHANNEL_ALLOC", "LOCAL_OK",
                    "channel=$s taken=${taken.size}/$MAX")
                return s
            }
        }
        val orphans = ChannelClaims.selectAll()
            .count { ClaimState.parse(it[ChannelClaims.state]) == ClaimState.ORPHAN }
        AppLogger.warn(AppLogger.Module.CAMERA, "CHANNEL_ALLOC", "EXHAUSTED",
            "max=$MAX orphan=$orphans — cần thu hồi ở màn dọn")
        throw ChannelSpaceExhausted(orphans)
    }

    /** Ghi nhận số này ĐÃ RA KHỎI MÁY. Gọi đúng lúc giao, không gọi lúc cấp. */
    fun claim(channelId: String, issuedTo: IssuedTo) {
        val existing = ChannelClaims.selectAll().firstOrNull { it[ChannelClaims.channelId] == channelId }
        if (existing == null) {
            ChannelClaims.insert {
                it[ChannelClaims.channelId] = channelId
                it[ChannelClaims.issuedTo] = issuedTo.wire
                it[ChannelClaims.state] = ClaimState.ACTIVE.wire
                it[ChannelClaims.claimedAt] = now()
                it[ChannelClaims.lastSeen] = now()
            }
            AppLogger.info(AppLogger.Module.CAMERA, "CHANNEL_ALLOC", "CLAIM_NEW",
                "channel=$channelId to=${issuedTo.wire}")
        } else {
            ChannelClaims.update({ ChannelClaims.channelId eq channelId }) {
                it[ChannelClaims.state] = ClaimState.ACTIVE.wire
                it[ChannelClaims.lastSeen] = now()
            }
        }
    }

    fun touch(channelId: String) {
        ChannelClaims.update({ ChannelClaims.channelId eq channelId }) {
            it[ChannelClaims.lastSeen] = now()
        }
    }

    /**
     * Camera rời số này. Có claim → `orphan` (bên ngoài còn nhớ, không được cấp lại).
     * Chưa từng ra ngoài → xoá dòng, số tự do ngay. Nhánh thứ hai là điều làm vòng lặp
     * thêm/xoá lúc dev không hao số nào.
     */
    fun orphanIfIssued(channelId: String) {
        val st = claimState(channelId) ?: return
        if (st == ClaimState.RELEASED) return
        ChannelClaims.update({ ChannelClaims.channelId eq channelId }) {
            it[ChannelClaims.state] = ClaimState.ORPHAN.wire
            it[ChannelClaims.lastSeen] = now()
        }
        AppLogger.info(AppLogger.Module.CAMERA, "CHANNEL_ALLOC", "ORPHANED",
            "channel=$channelId — bên ngoài có thể vẫn nhớ, cần thu hồi bằng tay")
    }

    /** Người vận hành xác nhận đã dọn ở bên ngoài → cấp lại được. */
    fun release(channelId: String) {
        ChannelClaims.update({ ChannelClaims.channelId eq channelId }) {
            it[ChannelClaims.state] = ClaimState.RELEASED.wire
            it[ChannelClaims.lastSeen] = now()
        }
        AppLogger.warn(AppLogger.Module.CAMERA, "CHANNEL_ALLOC", "RELEASED",
            "channel=$channelId — thu hồi bằng tay tại máy")
    }

    /**
     * Trần khai cho cloud qua `maxChannelNum`. Đây là đòn duy nhất ta có với một server hộp
     * đen: `maxChannelNum` là thông tin do NVR KHAI, nên lái được hành vi cấp số của cloud
     * mà không cần cloud biết.
     */
    fun localCeiling(): Int {
        val localCams = Cameras.selectAll().count { it[Cameras.owner] == "local" }
        val localBlocked = ChannelClaims.selectAll().count {
            ClaimState.parse(it[ChannelClaims.state]) != ClaimState.RELEASED &&
                IssuedTo.parse(it[ChannelClaims.issuedTo]) != IssuedTo.CLOUD &&
                Cameras.selectAll().none { c -> c[Cameras.channelId] == it[ChannelClaims.channelId] }
        }
        return (MAX - localCams - localBlocked).coerceAtLeast(0)
    }

    /** Dùng khi xoá hẳn một claim (chỉ cho test và cho lệnh dọn tại máy). */
    fun forget(channelId: String) {
        ChannelClaims.deleteWhere { ChannelClaims.channelId eq channelId }
    }
}
```

- [ ] **Step 4: Chạy test, kỳ vọng PASS**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.ChannelAllocatorTest"`
Expected: PASS, 10 test.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/vn/aiview/nvr/services/ChannelAllocator.kt backend/src/test
git commit -m "Feat(camera-identity): ChannelAllocator — cổng duy nhất cấp số kênh + claim"
```

---

### Task 5: Nối `ChannelAllocator` vào `CameraService`, bỏ `assignNextChannelIdLocked`

**Files:**
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/services/CameraService.kt:84` (slug), `:120` (cấp số), `:191-201` (xoá), `:220-225` (xoá hàm cũ)
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/routes/CameraRouting.kt:166` (bỏ bộ sinh slug thứ hai)
- Test: `backend/src/test/kotlin/vn/aiview/nvr/services/CameraSlugTest.kt`

**Interfaces:**
- Consumes: `ChannelAllocator.allocateLocal()`, `ChannelAllocator.orphanIfIssued()` từ Task 4
- Produces: `CameraService.slugForNewCamera(uid: String): String` = `"cam_" + uid.replace("-","").take(8)`

- [ ] **Step 1: Viết test cho slug**

`backend/src/test/kotlin/vn/aiview/nvr/services/CameraSlugTest.kt`:

```kotlin
package vn.aiview.nvr.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CameraSlugTest {
    @Test
    fun `slug sinh tu uid, khong tu channel_id`() {
        val a = CameraService.slugForNewCamera("11111111-2222-3333-4444-555555555555")
        assertEquals("cam_11111111", a)
    }

    @Test
    fun `hai camera khac uid thi khac slug du cung so kenh`() {
        val a = CameraService.slugForNewCamera("aaaaaaaa-0000-0000-0000-000000000000")
        val b = CameraService.slugForNewCamera("bbbbbbbb-0000-0000-0000-000000000000")
        assertNotEquals(a, b)
    }
}
```

- [ ] **Step 2: Chạy test, kỳ vọng FAIL**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.CameraSlugTest"`
Expected: FAIL — `slugForNewCamera` chưa tồn tại.

- [ ] **Step 3: Thêm `slugForNewCamera` và dùng nó**

Trong `CameraService.kt`, thêm hàm:

```kotlin
    /**
     * Slug của camera MỚI sinh từ `uid`, không từ `channel_id`.
     *
     * `slug` là tên camera trong config.yml của Frigate — tức tên thư mục ghi hình và khoá
     * của `ai_events`. `channel_id` bị tái sử dụng, nên `cam_$channelId` khiến hai camera
     * vật lý khác nhau chia nhau một chỗ chứa bản ghi: playback trong cửa sổ retention hiện
     * hình camera cũ dán nhãn camera mới.
     *
     * 8 hex đầu của UUIDv4 = 32 bit. Với ≤16 camera một máy, xác suất trùng bỏ qua được;
     * ràng buộc UNIQUE trên `slug` là chốt cuối nếu trùng thật.
     */
    fun slugForNewCamera(uid: String): String = "cam_" + uid.replace("-", "").take(8)
```

Thay dòng 84 `val slug = "cam_$channelId"` bằng:

```kotlin
        val existingRow = transaction {
            Cameras.selectAll().where { Cameras.channelId eq channelId }.firstOrNull()
        }
        val uid = existingRow?.get(Cameras.uid)?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString()
        val slug = existingRow?.get(Cameras.slug) ?: slugForNewCamera(uid)
```

Trong `applyAndPersistFromRtsp`, thay khối cấp số:

```kotlin
        val channelId = transaction {
            val existing = Cameras.selectAll().where { Cameras.slug eq slug }.firstOrNull()
            existing?.get(Cameras.channelId) ?: ChannelAllocator.allocateLocal()
        }
```

Xoá hoàn toàn `private fun assignNextChannelIdLocked()`.

- [ ] **Step 4: Cho `removeAndPersist` gọi `orphanIfIssued`**

Trong `CameraService.removeAndPersist`, ngay trước dòng log `CAMERA_REMOVE`:

```kotlin
        channelId?.let { ChannelAllocator.orphanIfIssued(it) }
```

- [ ] **Step 5: Bỏ bộ sinh slug thứ hai ở `CameraRouting`**

`CameraRouting.kt:166` hiện là:

```kotlin
val slug = "${name.lowercase().replace(Regex("[^a-z0-9]+"), "_")}_${ipAddr.replace(".", "_")}"
```

Đây là nguồn 15 stream rác `cam_c_ng_192_168_2_73` trong `config.yml`. Xoá dòng này và để route gọi `CameraService.applyAndPersistFromRtsp` với slug do `CameraService` tự sinh — đổi chữ ký `applyAndPersistFromRtsp` bỏ tham số `slug`, sinh bên trong bằng `slugForNewCamera(UUID.randomUUID().toString())`.

- [ ] **Step 6: Chạy toàn bộ test**

Run: `cd backend && ./gradlew test`
Expected: PASS toàn bộ.

- [ ] **Step 7: Build và chạy thật, thêm một camera từ UI**

```bash
./run.sh --rebuild
```

Thêm camera ở `https://<ip>` → Thiết lập → Danh sách Camera. Kiểm:

```bash
python3 -c "
import sqlite3;c=sqlite3.connect('data/nvr.db')
for r in c.execute('select slug,channel_id,owner,uid from cameras'): print(r)
"
grep -n 'cam_' config/config.yml | head
```

Expected: camera mới có `channel_id = 16`, `slug = cam_<8 hex>`, `owner = local`; `config.yml` có khoá camera đúng slug đó.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/vn/aiview/nvr backend/src/test
git commit -m "Feat(camera-identity): slug sinh từ uid, cấp số qua ChannelAllocator, gộp hai bộ sinh slug"
```

---

### Task 6: Ghi claim tại ba điểm số kênh ra khỏi máy

**Files:**
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/cloud/handlers/CameraCommandHandler.kt:144` (`postOrPutChannels`)
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/routes/IntegrationRouting.kt:167-178` (`/getprofilecamera`)
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/routes/BkavEventRouting.kt:225`
- Test: `backend/src/test/kotlin/vn/aiview/nvr/services/ClaimOnIssueTest.kt`

**Interfaces:**
- Consumes: `ChannelAllocator.claim`, `IssuedTo` từ Task 4
- Produces: không có API mới; ba call-site mới

- [ ] **Step 1: Viết test**

`backend/src/test/kotlin/vn/aiview/nvr/services/ClaimOnIssueTest.kt`:

```kotlin
package vn.aiview.nvr.services

import vn.aiview.nvr.TestDb
import vn.aiview.nvr.models.ChannelClaims
import vn.aiview.nvr.models.IssuedTo
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaimOnIssueTest {
    @Test
    fun `claim cloud ghi dung issued_to`() = TestDb.withCleanDb {
        ChannelAllocator.claim("1", IssuedTo.CLOUD)
        assertEquals("cloud", ChannelClaims.selectAll().single()[ChannelClaims.issuedTo])
    }

    @Test
    fun `claim box va bkav ghi dung issued_to`() = TestDb.withCleanDb {
        ChannelAllocator.claim("2", IssuedTo.BOX)
        ChannelAllocator.claim("3", IssuedTo.BKAV)
        val byCh = ChannelClaims.selectAll().associate {
            it[ChannelClaims.channelId] to it[ChannelClaims.issuedTo]
        }
        assertEquals("box", byCh["2"])
        assertEquals("bkav", byCh["3"])
    }

    @Test
    fun `claim khong ha cap mot orphan ve active neu chua release`() = TestDb.withCleanDb {
        ChannelAllocator.claim("4", IssuedTo.BOX)
        ChannelAllocator.orphanIfIssued("4")
        ChannelAllocator.claim("4", IssuedTo.BOX)
        assertEquals("active", ChannelClaims.selectAll().single()[ChannelClaims.state])
    }
}
```

> Test thứ ba khẳng định hành vi có chủ ý: một số `orphan` được **cấp lại cho đúng bên đã
> giữ nó** thì trở lại `active` — đó là trường hợp Box hỏi lại camera cũ, không phải cấp cho
> camera khác. Cấp cho camera khác bị chặn ở `allocateLocal`, không ở đây.

- [ ] **Step 2: Chạy test, kỳ vọng PASS ngay**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.ClaimOnIssueTest"`
Expected: PASS, 3 test (logic đã có từ Task 4; test này chốt hợp đồng trước khi móc call-site).

- [ ] **Step 3: Móc vào đường cloud**

Trong `CameraCommandHandler.postOrPutChannels`, ngay sau khi lấy được `channelId` và **trước** khi gọi `CameraService`:

```kotlin
        transaction { ChannelAllocator.claim(channelId, IssuedTo.CLOUD) }
```

- [ ] **Step 4: Móc vào đường Box**

Trong `IntegrationRouting.kt`, trong khối `transaction { Cameras.selectAll().mapNotNull { … } }` của `/getprofilecamera`, sau khi dựng `BoxCameraProfile` cho một `chId`, thêm trước khi `return@mapNotNull`:

```kotlin
                            ChannelAllocator.claim(chId, IssuedTo.BOX)
```

- [ ] **Step 5: Móc vào đường camera Bkav**

Trong `BkavEventRouting.kt`, tại chỗ khớp camera theo `cam_id` (dòng 225), sau khi khớp thành công:

```kotlin
                    ChannelAllocator.claim(id.toString(), IssuedTo.BKAV)
```

- [ ] **Step 6: Chạy toàn bộ test + build**

Run: `cd backend && ./gradlew test && ./gradlew installDist`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/vn/aiview/nvr backend/src/test
git commit -m "Feat(camera-identity): ghi claim tại 3 điểm số kênh ra khỏi máy (cloud/Box/Bkav)"
```

---

### Task 7: `CloudChannelResolver` + lọc bề mặt hướng cloud + trần động

**Files:**
- Create: `backend/src/main/kotlin/vn/aiview/nvr/cloud/CloudChannelResolver.kt`
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/cloud/handlers/CameraCommandHandler.kt` (`getChannels` filter, `maxChannelNum`, mọi handler nhận `channel_id`)
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/cloud/SignalingManager.kt:168` (`getCameraStatusList`), `:411` (offer LIVE)
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/cloud/handlers/PlaybackCommandHandler.kt`
- Test: `backend/src/test/kotlin/vn/aiview/nvr/cloud/CloudChannelResolverTest.kt`

**Interfaces:**
- Consumes: `Cameras.owner` từ Task 2, `ChannelAllocator.localCeiling()` từ Task 4
- Produces:
  - `object CloudChannelResolver`
  - `fun resolve(channelId: String): ResultRow?` — null nếu không có camera nào `owner='cloud'` ở số đó
  - `fun cloudChannels(): List<ResultRow>` — mọi camera `owner='cloud'`
  - `fun denied(channelId: String, func: String): JsonObject` — `E0007` + log `CLOUD_CHANNEL_DENIED`

- [ ] **Step 1: Viết test**

`backend/src/test/kotlin/vn/aiview/nvr/cloud/CloudChannelResolverTest.kt`:

```kotlin
package vn.aiview.nvr.cloud

import org.jetbrains.exposed.sql.insert
import vn.aiview.nvr.TestDb
import vn.aiview.nvr.models.Cameras
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CloudChannelResolverTest {

    private fun cam(slug: String, ch: String, owner: String) = Cameras.insert {
        it[Cameras.slug] = slug
        it[Cameras.displayName] = slug
        it[Cameras.channelId] = ch
        it[Cameras.owner] = owner
        it[Cameras.uid] = slug
    }

    @Test
    fun `phan giai duoc camera cloud`() = TestDb.withCleanDb {
        cam("cam_a", "1", "cloud")
        assertNotNull(CloudChannelResolver.resolve("1"))
    }

    @Test
    fun `khong phan giai camera local`() = TestDb.withCleanDb {
        cam("cam_b", "16", "local")
        assertNull(CloudChannelResolver.resolve("16"))
    }

    @Test
    fun `so khong co camera nao thi null`() = TestDb.withCleanDb {
        assertNull(CloudChannelResolver.resolve("7"))
    }

    @Test
    fun `cloudChannels chi tra camera cloud`() = TestDb.withCleanDb {
        cam("cam_a", "1", "cloud")
        cam("cam_b", "16", "local")
        val list = CloudChannelResolver.cloudChannels()
        assertEquals(1, list.size)
        assertEquals("1", list.single()[Cameras.channelId])
    }
}
```

- [ ] **Step 2: Chạy test, kỳ vọng FAIL**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.cloud.CloudChannelResolverTest"`
Expected: FAIL — `CloudChannelResolver` chưa tồn tại.

- [ ] **Step 3: Viết resolver**

`backend/src/main/kotlin/vn/aiview/nvr/cloud/CloudChannelResolver.kt`:

```kotlin
package vn.aiview.nvr.cloud

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.utils.AppLogger

/**
 * Cửa duy nhất để một lệnh từ cloud tìm ra camera.
 *
 * Lọc danh sách trong `get_channels` và trong heartbeat là CẦN nhưng một mình nó là mỹ phẩm:
 * cloud không cần danh sách để hỏi. Đã đo 21-08-2026: cloud giữ bản ghi kênh 1 từ 03-08 và
 * gửi thẳng offer LIVE cho kênh 1. Đường live phân giải theo `${channelId}_main`, nên nếu
 * kênh 1 giờ là camera local thì cloud nhận VIDEO THẬT của camera local dán nhãn cũ — lỗi
 * tệ nhất trong cả bài, và lọc danh sách không chạm tới nó.
 *
 * Whitelist chứ không blacklist: mọi cửa vào từ cloud phải đi qua đây. Cửa nào quên móc thì
 * hỏng chức năng, không rò dữ liệu — sai theo chiều an toàn.
 */
object CloudChannelResolver {

    fun resolve(channelId: String): ResultRow? =
        Cameras.selectAll().firstOrNull {
            it[Cameras.channelId] == channelId && it[Cameras.owner] == "cloud"
        }

    fun cloudChannels(): List<ResultRow> =
        Cameras.selectAll().filter { it[Cameras.owner] == "cloud" }

    fun denied(channelId: String, func: String): JsonObject {
        AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "CLOUD_CHANNEL_DENIED",
            "func=$func channel=$channelId — không thuộc camera do cloud quản lý")
        return CommandDispatcher.errorResponse("E0007",
            "Kênh $channelId không thuộc camera do Cloud quản lý")
    }
}
```

- [ ] **Step 4: Chạy test, kỳ vọng PASS**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.cloud.CloudChannelResolverTest"`
Expected: PASS, 4 test.

- [ ] **Step 5: Móc vào mọi cửa cloud**

Trong `CameraCommandHandler`:
- `getChannels()`: đổi `Cameras.selectAll()` thành `CloudChannelResolver.cloudChannels()`
- `maxChannelNum`: đổi `DeviceConstants.NUMBER_OF_CHANNEL` thành `ChannelAllocator.localCeiling()`
- `delChannels`, `getProfilesChannel`, `getSnapshot`, `getVideoSources`, `getAiEventImage`: đầu hàm, sau khi lấy `channelId`, thêm:

```kotlin
        if (CloudChannelResolver.resolve(channelId) == null) {
            return CloudChannelResolver.denied(channelId, "<tên func>")
        }
```

Trong `SignalingManager.getCameraStatusList()`: đổi

```kotlin
Cameras.selectAll().mapNotNull { row -> row[Cameras.channelId] }
```

thành

```kotlin
CloudChannelResolver.cloudChannels().mapNotNull { row -> row[Cameras.channelId] }
```

Trong `SignalingManager`, tại chỗ dựng tên stream cho offer LIVE (dòng ~579 `"${channelId}_${streamType}"`), thêm ngay trước:

```kotlin
            if (transaction { CloudChannelResolver.resolve(channelId) } == null) {
                AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "CLOUD_CHANNEL_DENIED",
                    "live offer channel=$channelId — không thuộc camera cloud")
                return null
            }
```

Trong `PlaybackCommandHandler`, mọi hàm nhận `channel_id`: chèn cùng một chốt như trên.

- [ ] **Step 6: Chạy test + build**

Run: `cd backend && ./gradlew test && ./gradlew installDist`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Kiểm thật với cloud**

```bash
./run.sh --rebuild
```

Chờ ~1 phút rồi:

```bash
grep -h "HEARTBEAT_SENT" logs/nvr.log | tail -3
grep -h "maxChannelNum" logs/nvr.log | tail -1 | grep -o 'maxChannelNum":[0-9]*'
```

Expected: heartbeat chỉ liệt kê kênh của camera `owner='cloud'`; `maxChannelNum` = `16 −` số camera local.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/vn/aiview/nvr/cloud backend/src/test
git commit -m "Feat(camera-identity): CloudChannelResolver chặn mọi cửa cloud + trần maxChannelNum động"
```

---

### Task 8: `Actor` + khoá RUD camera cloud ở `CameraService`

**Files:**
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/services/CameraService.kt`
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/routes/CameraRouting.kt` (truyền `Actor.LOCAL_UI`, map lỗi → 403)
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/cloud/handlers/CameraCommandHandler.kt` (truyền `Actor.CLOUD`)
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/services/GatewayService.kt` (truyền `Actor.GATEWAY`)
- Test: `backend/src/test/kotlin/vn/aiview/nvr/services/CameraOwnershipTest.kt`

**Interfaces:**
- Consumes: `Cameras.owner` từ Task 2
- Produces:
  - `enum class Actor { LOCAL_UI, CLOUD, GATEWAY, SYSTEM }` trong `CameraService.kt`
  - `class CloudManagedCamera(val slug: String) : Exception`
  - `fun CameraService.assertMutable(slug: String, actor: Actor, field: String?)` — ném `CloudManagedCamera` nếu `actor == LOCAL_UI` và camera `owner='cloud'` và `field` nằm trong danh sách khoá
  - `val CLOUD_LOCKED_FIELDS: Set<String>` = `setOf("ip", "onvif_port", "username", "password", "rtsp_url", "sub_rtsp_url")`

- [ ] **Step 1: Viết test**

`backend/src/test/kotlin/vn/aiview/nvr/services/CameraOwnershipTest.kt`:

```kotlin
package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.insert
import vn.aiview.nvr.TestDb
import vn.aiview.nvr.models.Cameras
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CameraOwnershipTest {

    private fun cam(slug: String, owner: String) = Cameras.insert {
        it[Cameras.slug] = slug
        it[Cameras.displayName] = slug
        it[Cameras.channelId] = "1"
        it[Cameras.owner] = owner
        it[Cameras.uid] = slug
    }

    @Test
    fun `LOCAL_UI khong xoa duoc camera cloud`() = TestDb.withCleanDb {
        cam("cam_c", "cloud")
        assertFailsWith<CloudManagedCamera> {
            CameraService.assertMutable("cam_c", Actor.LOCAL_UI, field = null)
        }
    }

    @Test
    fun `LOCAL_UI khong sua duoc ip camera cloud`() = TestDb.withCleanDb {
        cam("cam_c", "cloud")
        assertFailsWith<CloudManagedCamera> {
            CameraService.assertMutable("cam_c", Actor.LOCAL_UI, field = "ip")
        }
    }

    @Test
    fun `LOCAL_UI sua duoc ptz_mode camera cloud`() = TestDb.withCleanDb {
        cam("cam_c", "cloud")
        CameraService.assertMutable("cam_c", Actor.LOCAL_UI, field = "ptz_mode")
    }

    @Test
    fun `LOCAL_UI sua duoc display_name camera cloud`() = TestDb.withCleanDb {
        cam("cam_c", "cloud")
        CameraService.assertMutable("cam_c", Actor.LOCAL_UI, field = "display_name")
    }

    @Test
    fun `CLOUD xoa duoc camera cua chinh no`() = TestDb.withCleanDb {
        cam("cam_c", "cloud")
        CameraService.assertMutable("cam_c", Actor.CLOUD, field = null)
    }

    @Test
    fun `GATEWAY xoa duoc camera cloud`() = TestDb.withCleanDb {
        cam("cam_c", "cloud")
        CameraService.assertMutable("cam_c", Actor.GATEWAY, field = null)
    }

    @Test
    fun `LOCAL_UI xoa duoc camera local`() = TestDb.withCleanDb {
        cam("cam_l", "local")
        CameraService.assertMutable("cam_l", Actor.LOCAL_UI, field = null)
    }
}
```

> `display_name` **thả** là có chủ ý và khác bản thiết kế đầu: cloud không gửi tên xuống
> (đo được 21-08 16:45, rename trên dashboard không kèm trường tên), nên khoá nó là đóng
> băng camera ở tên `Camera N` vĩnh viễn.

- [ ] **Step 2: Chạy test, kỳ vọng FAIL**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.CameraOwnershipTest"`
Expected: FAIL — `Actor`/`CloudManagedCamera`/`assertMutable` chưa tồn tại.

- [ ] **Step 3: Viết cơ chế khoá**

Thêm vào đầu `CameraService.kt` (ngoài `object`):

```kotlin
/** Ai đang gọi. Quyết định camera `owner='cloud'` có sửa/xoá được hay không. */
enum class Actor { LOCAL_UI, CLOUD, GATEWAY, SYSTEM }

/** Web UI cố sửa/xoá camera do cloud quản lý. Route map thành 403 CLOUD_MANAGED. */
class CloudManagedCamera(val slug: String) : Exception(
    "Camera này do Cloud quản lý. Sửa hoặc xoá trên dashboard Cloud, hoặc tắt Kết nối Cloud " +
        "trong Thiết lập để giải phóng."
)
```

Trong `object CameraService`:

```kotlin
    /**
     * Trường mà `put_channels` ghi → cloud sở hữu → Web UI không sửa.
     *
     * Quy tắc tự bảo trì: khoá ĐÚNG những trường cloud ghi, thả mọi thứ còn lại. Khoá cả
     * camera là quá tay — lịch ghi hình, override `ptz_mode`, zone AI đều là việc của NVR,
     * cloud không quản. `display_name` KHÔNG nằm ở đây vì cloud không gửi tên xuống.
     */
    val CLOUD_LOCKED_FIELDS = setOf("ip", "onvif_port", "username", "password", "rtsp_url", "sub_rtsp_url")

    /**
     * Chốt khoá đặt ở CỔ CHAI, không ở route. Ẩn nút không phải khoá (curl vẫn vào được), và
     * chặn từng route thì đường thứ ba xuất hiện là hở — `GatewayService` đang gọi thẳng
     * `removeAndPersist`.
     *
     * @param field null = xoá camera. Khác null = tên cột đang sửa.
     */
    fun assertMutable(slug: String, actor: Actor, field: String?) {
        if (actor != Actor.LOCAL_UI) return
        val owner = transaction {
            Cameras.selectAll().firstOrNull { it[Cameras.slug] == slug }?.get(Cameras.owner)
        } ?: return
        if (owner != "cloud") return
        if (field != null && field !in CLOUD_LOCKED_FIELDS) return
        AppLogger.warn(AppLogger.Module.SECURITY, "CAMERA_LOCK", "CLOUD_MANAGED_DENIED",
            "slug=$slug field=${field ?: "(delete)"} actor=$actor")
        throw CloudManagedCamera(slug)
    }
```

- [ ] **Step 4: Chạy test, kỳ vọng PASS**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.CameraOwnershipTest"`
Expected: PASS, 7 test.

- [ ] **Step 5: Móc vào các call-site**

`CameraRouting.kt`:
- Trước `removeAndPersist` (dòng ~311): `CameraService.assertMutable(cameraId, Actor.LOCAL_UI, null)`
- Trong `Cameras.update` (dòng ~249 và ~760): gọi `assertMutable(id, Actor.LOCAL_UI, field)` cho từng trường đang sửa
- Bọc handler bằng:

```kotlin
            } catch (e: CloudManagedCamera) {
                return@delete call.respond(HttpStatusCode.Forbidden,
                    mapOf("error" to "CLOUD_MANAGED", "message" to (e.message ?: "")))
            }
```

`CameraCommandHandler`: đường `postOrPutChannels` và `delChannels` đặt `owner = "cloud"` khi upsert, và gọi với `Actor.CLOUD`.

`GatewayService.kt:356` và `:422`: gọi với `Actor.GATEWAY`, và với mỗi camera `owner='cloud'` bị xoá thì `ChannelAllocator.orphanIfIssued(channelId)` + `AppLogger.warn(GATEWAY, "REMOVE", "CLOUD_CAMERA_REMOVED", …)`.

- [ ] **Step 6: Chạy test + build + kiểm bằng curl**

```bash
cd backend && ./gradlew test && ./gradlew installDist && cd .. && ./run.sh --rebuild
```

Với session hợp lệ (lấy cookie từ browser devtools), thử xoá camera cloud:

```bash
curl -k -X DELETE https://localhost/api/cameras/cam_2 -H "Cookie: <session>" -i | head -5
```

Expected: `HTTP/1.1 403 Forbidden` và body chứa `CLOUD_MANAGED`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/vn/aiview/nvr backend/src/test
git commit -m "Feat(camera-identity): khoá sửa/xoá camera cloud từ Web UI, cưỡng chế ở CameraService"
```

---

### Task 9: `put_channels` idempotent + tên mặc định từ ONVIF

**Files:**
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/cloud/handlers/CameraCommandHandler.kt:144`
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/services/CameraService.kt` (`applyAndPersistFromOnvif`)
- Test: `backend/src/test/kotlin/vn/aiview/nvr/services/CameraNamingTest.kt`

**Interfaces:**
- Consumes: `CloudChannelResolver.resolve` từ Task 7
- Produces:
  - `fun CameraService.defaultDisplayName(manufacturer: String, model: String, ip: String, channelId: String, nameTaken: (String) -> Boolean): String`
  - `fun CameraCommandHandler.isUnchanged(channelId: String, host: String, port: Int, user: String, pass: String): Boolean`

- [ ] **Step 1: Viết test**

`backend/src/test/kotlin/vn/aiview/nvr/services/CameraNamingTest.kt`:

```kotlin
package vn.aiview.nvr.services

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraNamingTest {
    @Test
    fun `ten mac dinh la hang cong model`() {
        assertEquals("AI VIEW P200-0802N",
            CameraService.defaultDisplayName("AI VIEW", "P200-0802N", "192.168.1.8", "2") { false })
    }

    @Test
    fun `trung ten thi them ip`() {
        assertEquals("AI VIEW P200-0802N (192.168.1.8)",
            CameraService.defaultDisplayName("AI VIEW", "P200-0802N", "192.168.1.8", "2") {
                it == "AI VIEW P200-0802N"
            })
    }

    @Test
    fun `probe khong ra gi thi roi ve Camera N`() {
        assertEquals("Camera 2",
            CameraService.defaultDisplayName("Unknown", "Unknown", "192.168.1.8", "2") { false })
    }

    @Test
    fun `chi co hang khong co model`() {
        assertEquals("AI VIEW",
            CameraService.defaultDisplayName("AI VIEW", "Unknown", "192.168.1.8", "2") { false })
    }
}
```

- [ ] **Step 2: Chạy test, kỳ vọng FAIL**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.CameraNamingTest"`
Expected: FAIL — `defaultDisplayName` chưa tồn tại.

- [ ] **Step 3: Viết `defaultDisplayName`**

Trong `object CameraService`:

```kotlin
    /**
     * Tên mặc định cho camera cloud thêm vào.
     *
     * Cloud KHÔNG gửi tên xuống — payload `put_channels` chỉ có channelID/host/port/https/
     * username/password, kể cả khi rename trên dashboard (đo 21-08-2026 16:45). Nên `Camera N`
     * là tất cả những gì NVR từng hiển thị. Nhưng `applyAndPersistFromOnvif` đã probe được
     * `manufacturer`/`model` thật rồi VỨT ĐI (ghi tham số của caller, mặc định "Unknown") —
     * dùng lại chúng là đủ để có tên tử tế mà không cần cloud tham gia.
     */
    fun defaultDisplayName(
        manufacturer: String,
        model: String,
        ip: String,
        channelId: String,
        nameTaken: (String) -> Boolean,
    ): String {
        val parts = listOf(manufacturer, model)
            .filter { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
        if (parts.isEmpty()) return "Camera $channelId"
        val base = parts.joinToString(" ")
        return if (nameTaken(base)) "$base ($ip)" else base
    }
```

- [ ] **Step 4: Chạy test, kỳ vọng PASS**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.CameraNamingTest"`
Expected: PASS, 4 test.

- [ ] **Step 5: Dùng kết quả probe trong `applyAndPersistFromOnvif`**

Trong `applyAndPersistFromOnvif`, sau khi có `info`, thay hai đối số truyền vào `configMutexApply`:

```kotlin
        val effManufacturer = manufacturer.takeIf { it != "Unknown" && it.isNotBlank() }
            ?: info.manufacturer
        val effModel = model.takeIf { it != "Unknown" && it.isNotBlank() } ?: info.model
        val effName = displayName.takeIf { it.isNotBlank() && !it.startsWith("Camera ") }
            ?: defaultDisplayName(effManufacturer, effModel, host, channelId) { candidate ->
                transaction { Cameras.selectAll().any { it[Cameras.displayName] == candidate } }
            }
```

rồi truyền `manufacturer = effManufacturer`, `model = effModel`, `displayName = effName`.

- [ ] **Step 6: Làm `put_channels` idempotent**

Trong `CameraCommandHandler.postOrPutChannels`, ngay sau khi đọc xong `channelId`/`host`/`port`/`user`/`pass` và **trước** khi gọi `CameraService`:

```kotlin
        // Đổi tên camera trên dashboard làm cloud gửi lại put_channels với payload Y NGUYÊN.
        // Đường apply thì ghi config.yml + restart Frigate bất kể có đổi gì thật — đo được
        // 21-08 16:45: một lần rename = ~13 giây Frigate không phản hồi = một lỗ ghi hình.
        val unchanged = transaction {
            CloudChannelResolver.resolve(channelId)?.let { row ->
                row[Cameras.ip] == host &&
                    row[Cameras.onvifPort] == port &&
                    row[Cameras.username] == user
            } ?: false
        }
        if (unchanged) {
            transaction { ChannelAllocator.touch(channelId) }
            AppLogger.info(AppLogger.Module.SYSTEM, "CLOUD", "PUT_CHANNELS_NOOP",
                "channel=$channelId host=$host — payload không đổi, bỏ qua apply + restart Frigate")
            return CommandDispatcher.let { crudSuccess("put.device.success") }
        }
```

> Cố tình **không** so `password`: mật khẩu trong DB được mã hoá, giải ra để so là mở rộng
> bề mặt xử lý mật khẩu thô cho một tối ưu. Host/port/user không đổi đã bắt được đúng ca
> rename, là ca gây hại thật.

- [ ] **Step 7: Chạy test + build + kiểm thật**

```bash
cd backend && ./gradlew test && cd .. && ./run.sh --rebuild
```

Trên dashboard cloud, đổi tên camera. Rồi:

```bash
grep -h "PUT_CHANNELS_NOOP\|ONLINE_TRACKER_RESTART_HOLD" logs/nvr.log | tail -5
```

Expected: có `PUT_CHANNELS_NOOP`, **không** có `ONLINE_TRACKER_RESTART_HOLD` mới.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/vn/aiview/nvr backend/src/test
git commit -m "Fix(cloud): put_channels idempotent theo nội dung + tên mặc định lấy từ ONVIF probe"
```

---

### Task 10: Đường thoát khỏi khoá + API màn dọn

**Files:**
- Create: `backend/src/main/kotlin/vn/aiview/nvr/services/CloudOwnershipRelease.kt`
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/routes/CloudRouting.kt` (gọi release khi tắt cloud; endpoint claims)
- Test: `backend/src/test/kotlin/vn/aiview/nvr/services/CloudOwnershipReleaseTest.kt`

**Interfaces:**
- Consumes: `ChannelAllocator`, `Cameras.owner`
- Produces:
  - `fun releaseAllCloudCameras(): Int` — mỗi camera `owner='cloud'` → `owner='local'`, số cũ → `orphan`, cấp số local mới, trả số camera đã giải phóng
  - `fun cloudDisconnectedDays(): Long`
  - `GET /api/cameras/channel-claims` → `[{channelId, issuedTo, state, claimedAt, cameraSlug|null, cameraName|null}]`
  - `POST /api/cameras/channel-claims/{id}/release`

- [ ] **Step 1: Viết test**

`backend/src/test/kotlin/vn/aiview/nvr/services/CloudOwnershipReleaseTest.kt`:

```kotlin
package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import vn.aiview.nvr.TestDb
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.models.ChannelClaims
import vn.aiview.nvr.models.IssuedTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CloudOwnershipReleaseTest {

    private fun cam(slug: String, ch: String, owner: String) = Cameras.insert {
        it[Cameras.slug] = slug
        it[Cameras.displayName] = slug
        it[Cameras.channelId] = ch
        it[Cameras.owner] = owner
        it[Cameras.uid] = slug
    }

    @Test
    fun `giai phong doi owner sang local`() = TestDb.withCleanDb {
        cam("cam_c", "1", "cloud")
        ChannelAllocator.claim("1", IssuedTo.CLOUD)
        assertEquals(1, releaseAllCloudCameras())
        assertEquals("local", Cameras.selectAll().single()[Cameras.owner])
    }

    @Test
    fun `giai phong doi so sang dai local`() = TestDb.withCleanDb {
        cam("cam_c", "1", "cloud")
        ChannelAllocator.claim("1", IssuedTo.CLOUD)
        releaseAllCloudCameras()
        assertNotEquals("1", Cameras.selectAll().single()[Cameras.channelId])
    }

    @Test
    fun `so cu thanh orphan chu khong tu do`() = TestDb.withCleanDb {
        cam("cam_c", "1", "cloud")
        ChannelAllocator.claim("1", IssuedTo.CLOUD)
        releaseAllCloudCameras()
        val old = ChannelClaims.selectAll().single { it[ChannelClaims.channelId] == "1" }
        assertEquals("orphan", old[ChannelClaims.state])
    }

    @Test
    fun `khong co camera cloud thi tra 0`() = TestDb.withCleanDb {
        cam("cam_l", "16", "local")
        assertEquals(0, releaseAllCloudCameras())
    }
}
```

- [ ] **Step 2: Chạy test, kỳ vọng FAIL**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.CloudOwnershipReleaseTest"`
Expected: FAIL — `releaseAllCloudCameras` chưa tồn tại.

- [ ] **Step 3: Viết release**

`backend/src/main/kotlin/vn/aiview/nvr/services/CloudOwnershipRelease.kt`:

```kotlin
package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.utils.AppLogger

/**
 * Đường thoát khỏi khoá "camera do Cloud quản lý".
 *
 * Vì sao phải có: khoá cứng mà không có đường thoát thì máy tự khoá mình. Khách ngừng dùng
 * cloud, server đổi domain, hay tài khoản bị xoá — những camera đó thành không sửa được,
 * không xoá được, mãi mãi, kể cả khi camera đã tháo khỏi tường.
 *
 * Vì sao KHÔNG tự động theo `connectionLost`: tín hiệu đó nổ y như nhau khi rớt mạng 10 giây,
 * broker restart, hay đổi IP WAN. Tự mở khoá theo nó là dựng lại đúng cái lỗ vừa bịt — rớt
 * mạng một nhịp → mở khoá → người vận hành xoá camera → mạng về → cloud vẫn còn bản ghi, số
 * đó trống cho camera khác chiếm. `device_service` cũng không có func nào để cloud nói "tôi
 * đã xoá thiết bị này" (đã soi hết: get_information/get_time/get_ethernet/get_wifi/
 * get_user_management/get_storages/post_reboot).
 *
 * Nên chỉ chạy khi người vận hành CỐ Ý tắt Kết nối Cloud, hoặc bấm nút trên dải cảnh báo
 * "mất kết nối quá N ngày".
 */
fun releaseAllCloudCameras(): Int {
    val cloudCams = Cameras.selectAll().filter { it[Cameras.owner] == "cloud" }
    if (cloudCams.isEmpty()) return 0

    var freed = 0
    for (row in cloudCams) {
        val slug = row[Cameras.slug]
        val oldCh = row[Cameras.channelId]

        // Dời số sang dải local là BẮT BUỘC, không tuỳ chọn: để nguyên số cũ thì hôm sau
        // khách nối lại cloud, cloud cấp put_channels đúng số đó và ta về vạch xuất phát.
        val newCh = try {
            ChannelAllocator.allocateLocal()
        } catch (e: ChannelSpaceExhausted) {
            AppLogger.error(AppLogger.Module.CAMERA, "CLOUD_RELEASE", "NO_LOCAL_CHANNEL",
                "slug=$slug giữ nguyên số $oldCh — hết số local, cần thu hồi ở màn dọn", e)
            continue
        }

        Cameras.update({ Cameras.slug eq slug }) {
            it[Cameras.owner] = "local"
            it[Cameras.channelId] = newCh
        }
        oldCh?.let { ChannelAllocator.orphanIfIssued(it) }
        ChannelAllocator.claim(newCh, vn.aiview.nvr.models.IssuedTo.BOX)
        freed++

        AppLogger.warn(AppLogger.Module.CAMERA, "CLOUD_RELEASE", "FREED",
            "slug=$slug channel=$oldCh→$newCh — camera trở thành cục bộ, số cũ thành orphan")
    }

    AppLogger.warn(AppLogger.Module.CAMERA, "CLOUD_RELEASE", "DONE",
        "freed=$freed/${cloudCams.size} — bản ghi cũ vẫn còn trên dashboard Cloud tới khi người vận hành tự xoá")
    return freed
}
```

> `claim(newCh, BOX)` cho số mới: số vừa cấp sẽ được trả cho Box ở lần `/getprofilecamera`
> kế tiếp, và giữ nó ở trạng thái `active` ngay từ đây tránh một cửa sổ mà số đó bị cấp cho
> camera khác giữa hai nhịp.

- [ ] **Step 4: Chạy test, kỳ vọng PASS**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.CloudOwnershipReleaseTest"`
Expected: PASS, 4 test.

- [ ] **Step 5: Móc vào tắt cloud + thêm hai endpoint**

Trong `CloudRouting.kt`, ở đường tắt `enabled`: sau khi ghi `cloud_config.enabled = false`, gọi `transaction { releaseAllCloudCameras() }` và trả về số camera đã giải phóng để UI hiện.

Thêm vào `CameraRouting.kt` (trong khối `authenticate`):

```kotlin
        get("/api/cameras/channel-claims") {
            val list = transaction {
                ChannelClaims.selectAll().map { c ->
                    val ch = c[ChannelClaims.channelId]
                    val cam = Cameras.selectAll().firstOrNull { it[Cameras.channelId] == ch }
                    mapOf(
                        "channelId" to ch,
                        "issuedTo" to c[ChannelClaims.issuedTo],
                        "state" to c[ChannelClaims.state],
                        "claimedAt" to c[ChannelClaims.claimedAt],
                        "cameraSlug" to cam?.get(Cameras.slug),
                        "cameraName" to cam?.get(Cameras.displayName),
                    )
                }.sortedBy { (it["channelId"] as String).toIntOrNull() ?: 99 }
            }
            call.respond(list)
        }

        post("/api/cameras/channel-claims/{id}/release") {
            val id = call.parameters["id"]
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing id"))
            val inUse = transaction { Cameras.selectAll().any { it[Cameras.channelId] == id } }
            if (inUse) {
                return@post call.respond(HttpStatusCode.Conflict,
                    mapOf("error" to "IN_USE", "message" to "Kênh $id đang có camera dùng, không thu hồi được."))
            }
            transaction { ChannelAllocator.release(id) }
            call.respond(mapOf("ok" to true))
        }
```

- [ ] **Step 6: Chạy test + build**

Run: `cd backend && ./gradlew test && ./gradlew installDist`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/vn/aiview/nvr backend/src/test
git commit -m "Feat(camera-identity): đường thoát giải phóng camera cloud + API màn dọn số kênh"
```

---

### Task 11: Frontend — ổ khoá, dải cảnh báo, màn dọn

**Files:**
- Modify: `frontend/src/components/SetupView.tsx` (thêm tab)
- Create: `frontend/src/components/ChannelClaimsPanel.tsx`
- Modify: `frontend/src/components/CameraGridItem.tsx` hoặc component render dòng camera trong Danh sách Camera (thêm badge + disable nút)
- Modify: `frontend/src/components/CloudSettingsSection.tsx` (hộp xác nhận khi tắt + dải cảnh báo 7 ngày)

**Interfaces:**
- Consumes: `GET /api/cameras/channel-claims`, `POST /api/cameras/channel-claims/{id}/release` từ Task 10; trường `owner` trong response `GET /api/cameras`
- Produces: tab `{ id: 'Channels', label: 'Số kênh' }` trong `SetupView`

- [ ] **Step 1: Trả `owner` ra API camera**

Trong `CameraRouting.kt:94` và `:116`, thêm `owner = row[Cameras.owner]` vào DTO, và thêm field vào data class tương ứng. Kiểm:

```bash
curl -k https://localhost/api/cameras -H "Cookie: <session>" | python3 -m json.tool | grep -c owner
```

Expected: bằng số camera.

- [ ] **Step 2: Viết `ChannelClaimsPanel.tsx`**

```tsx
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

type Claim = {
  channelId: string
  issuedTo: 'cloud' | 'box' | 'bkav'
  state: 'active' | 'orphan' | 'released'
  claimedAt: string
  cameraSlug: string | null
  cameraName: string | null
}

const ISSUED_LABEL: Record<Claim['issuedTo'], string> = {
  cloud: 'Cloud', box: 'AiView Box', bkav: 'Camera Bkav',
}

export default function ChannelClaimsPanel() {
  const { t } = useTranslation()
  const [claims, setClaims] = useState<Claim[]>([])
  const [busy, setBusy] = useState<string | null>(null)

  const load = () =>
    fetch('/api/cameras/channel-claims').then(r => r.json()).then(setClaims)

  useEffect(() => { load() }, [])

  const release = async (id: string) => {
    const ok = window.confirm(
      `Trước khi thu hồi kênh ${id}: hãy xoá kênh đó trên dashboard Cloud (hoặc bỏ camera trên Box).\n\n` +
      `Nếu chưa xoá mà thu hồi, camera thêm sau sẽ hiện SAI TÊN trên dashboard.\n\nThu hồi kênh ${id}?`
    )
    if (!ok) return
    setBusy(id)
    try {
      const res = await fetch(`/api/cameras/channel-claims/${id}/release`, { method: 'POST' })
      if (!res.ok) alert((await res.json()).message ?? 'Không thu hồi được')
      await load()
    } finally { setBusy(null) }
  }

  return (
    <div className="space-y-3">
      <p className="text-sm opacity-70">
        {t('Những số kênh NVR đã giao ra ngoài. Cloud và Box tự nhớ số ở phía họ và NVR không xoá hộ được — muốn dùng lại một số thì phải dọn ở đó trước.')}
      </p>
      <table className="w-full text-sm">
        <thead><tr className="text-left opacity-60">
          <th className="py-1">{t('Kênh')}</th><th>{t('Đã giao cho')}</th>
          <th>{t('Camera hiện tại')}</th><th>{t('Trạng thái')}</th><th />
        </tr></thead>
        <tbody>
          {claims.map(c => (
            <tr key={c.channelId} className="border-t border-white/10">
              <td className="py-2 font-mono">{c.channelId}</td>
              <td>{ISSUED_LABEL[c.issuedTo]}</td>
              <td>{c.cameraName ?? <span className="opacity-50">{t('— không còn —')}</span>}</td>
              <td>{c.state === 'active' ? t('đang dùng') : c.state === 'orphan' ? t('chờ dọn') : t('đã thu hồi')}</td>
              <td className="text-right">
                {c.state === 'orphan' && (
                  <button disabled={busy === c.channelId}
                    onClick={() => release(c.channelId)}
                    className="px-2 py-1 rounded bg-amber-600/80 hover:bg-amber-600 disabled:opacity-40">
                    {t('Thu hồi')}
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
```

- [ ] **Step 3: Thêm tab vào `SetupView.tsx`**

Trong mảng tab (khoảng dòng 100), thêm sau `Camera List`:

```tsx
    { id: 'Channels', icon: 'confirmation_number', label: t('Số kênh') },
```

và trong khối render theo `setupTab`, thêm nhánh `{setupTab === 'Channels' && <ChannelClaimsPanel />}`.

> `CLAUDE.md` §4 cảnh báo `id` của tab khác nhãn hiển thị và trộn Anh/Việt — dùng `id` mới
> `Channels`, không sửa `id` nào đang có, để không làm tab khác trắng.

- [ ] **Step 4: Ổ khoá trên dòng camera**

Trong component render dòng camera của Danh sách Camera, khi `cam.owner === 'cloud'`:

```tsx
{cam.owner === 'cloud' && (
  <span title={t('Camera do Cloud quản lý. Sửa/xoá trên dashboard Cloud. Tên trên dashboard có thể khác vì Cloud không gửi tên xuống NVR.')}
        className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded bg-sky-500/20 text-sky-300">
    <Icon name="lock" className="text-xs" />{t('Cloud')}
  </span>
)}
```

và trên nút Sửa/Xoá: `disabled={cam.owner === 'cloud'}` kèm `title` cùng nội dung.

- [ ] **Step 5: Hộp xác nhận khi tắt Cloud**

Trong `CloudSettingsSection.tsx`, trước khi gửi request tắt:

```tsx
const cloudCams = cameras.filter(c => c.owner === 'cloud').length
if (cloudCams > 0 && !window.confirm(
  `${cloudCams} camera đang do Cloud quản lý sẽ trở thành camera cục bộ và bạn sửa/xoá được.\n\n` +
  `Chúng vẫn còn trên dashboard Cloud tới khi bạn tự xoá ở đó.\n\nTiếp tục tắt Kết nối Cloud?`
)) return
```

- [ ] **Step 6: Build frontend**

Run: `cd frontend && npm run lint && npm run build`
Expected: không lỗi eslint, build thành công.

- [ ] **Step 7: Kiểm bằng mắt trên máy dev**

```bash
./run.sh --rebuild
```

Mở `https://<ip>` → Thiết lập: dòng camera cloud có badge ổ khoá, nút Sửa/Xoá xám; tab **Số kênh** liệt kê claim; tắt Kết nối Cloud hiện đúng hộp xác nhận.

- [ ] **Step 8: Commit**

```bash
git add frontend/src
git commit -m "Feat(camera-identity): UI ổ khoá camera cloud + tab Số kênh + xác nhận khi tắt Cloud"
```

---

### Task 12: E2E, tài liệu, changelog

**Files:**
- Create: `tests/e2e/tests/camera-identity.spec.ts`
- Modify: `CLAUDE.md`
- Modify: `docs/INDEX.md`
- Modify: `/home/toan/toanDoc/Documents/1CodeBkav/ChangeLog/NVR.md`

**Interfaces:**
- Consumes: mọi thứ ở trên
- Produces: không

- [ ] **Step 1: Viết E2E**

`tests/e2e/tests/camera-identity.spec.ts`:

```ts
import { test, expect } from '@playwright/test'
import { login } from '../helpers/auth'

test('camera do cloud quản lý không xoá được từ Web UI', async ({ page, request }) => {
  await login(page)
  await page.goto('/#setup')

  const res = await request.get('/api/cameras')
  const cams = await res.json()
  const cloudCam = cams.find((c: any) => c.owner === 'cloud')
  test.skip(!cloudCam, 'máy này chưa có camera do cloud thêm')

  const del = await request.delete(`/api/cameras/${cloudCam.id}`)
  expect(del.status()).toBe(403)
  expect((await del.json()).error).toBe('CLOUD_MANAGED')
})

test('tab Số kênh liệt kê claim', async ({ page }) => {
  await login(page)
  await page.goto('/#setup')
  await page.getByRole('button', { name: /Số kênh/ }).click()
  await expect(page.getByText(/Đã giao cho/)).toBeVisible()
})
```

> `helpers/auth` là helper đăng nhập đang dùng trong `tests/e2e/` — nếu tên khác, dùng đúng
> tên có sẵn ở đó chứ không tạo helper mới.

- [ ] **Step 2: Chạy E2E**

Run: `cd tests/e2e && npx playwright test tests/camera-identity.spec.ts`
Expected: PASS (test đầu có thể `skip` nếu máy không có camera cloud).

- [ ] **Step 3: Cập nhật `CLAUDE.md`**

Trong §3.1 (Thêm/xoá camera), thêm ngay sau sơ đồ code path:

```markdown
**Định danh camera — ba khái niệm, đừng lẫn:**

| | Là gì | Tái sử dụng |
|---|---|---|
| `uid` | UUIDv4 bất biến; `slug = cam_<uid8>` sinh từ đây | không bao giờ |
| `channel_id` | Số 1–16 **đã phát ra ngoài** (cloud/Box/camera Bkav); mọi camera đều có | có, chỉ qua `ChannelAllocator` |
| `owner` | `cloud` \| `local` — quyết định cloud có phân giải được camera đó không | — |

`channel_id` **không** còn sinh ra `slug`. Camera cũ giữ `slug = cam_<số>`, camera mới dùng
`cam_<uid8>` — **hai kiểu cùng tồn tại là có chủ ý**, không phải bug: đổi slug camera cũ
nghĩa là UPDATE hàng nghìn dòng `ai_events` + đổi tên thư mục media + đổi khoá `config.yml`.

- Không code nào ngoài `ChannelAllocator` được quyết định một `channel_id`.
- Mọi cửa vào từ cloud phải phân giải qua `CloudChannelResolver.resolve()` — lọc danh sách
  trong `get_channels`/heartbeat là **chưa đủ**, cloud gửi offer LIVE thẳng theo số kênh nó
  nhớ, và số đó có thể đã thuộc camera local.
- `maxChannelNum` khai cho cloud là **động** (`ChannelAllocator.localCeiling()`), không phải
  hằng 16. Đây là đòn duy nhất lái được hành vi cấp số của một server hộp đen.
- Cloud **không gửi tên camera** (kể cả khi rename). Tên trên dashboard và trong Web UI khác
  nhau là bình thường, không phải lỗi.
```

- [ ] **Step 4: Thêm spec + plan vào `docs/INDEX.md`**

```markdown
| [Tách định danh camera (spec)](superpowers/specs/2026-08-21-camera-identity-dual-crud-design.md) | ✅ CRUD hai nguồn không nói sai |
| [Tách định danh camera (plan)](superpowers/plans/2026-08-21-camera-identity-dual-crud.md) | ✅ kế hoạch thi hành |
```

- [ ] **Step 5: Append changelog**

Thêm vào cuối `/home/toan/toanDoc/Documents/1CodeBkav/ChangeLog/NVR.md`, theo đúng khuôn `CLAUDE.md` §0.2, mục `Công việc : sửa chữa`, `hiện trạng trước khi xử lý` mô tả: *dashboard cloud hiện camera "192.168.2.4" đang online trong khi NVR không có camera nào ở IP đó; playback có thể hiện hình camera cũ dán nhãn camera mới; đổi tên camera trên dashboard làm Frigate restart ~13 giây*. Ghi rõ nguyên nhân gốc (`channel_id` gánh hai vai, `assignNextChannelIdLocked` chỉ thấy trạng thái của NVR) và số test xanh thực tế.

- [ ] **Step 6: Commit**

```bash
git add tests/e2e CLAUDE.md docs/INDEX.md
git commit -m "Docs(camera-identity): E2E + CLAUDE.md + INDEX + changelog"
```

---

### Task 13: Lưới an toàn — cloud giành số camera local đang giữ (spec §4.3)

**Files:**
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/cloud/handlers/CameraCommandHandler.kt` (`postOrPutChannels`)
- Create: `backend/src/main/kotlin/vn/aiview/nvr/services/ChannelConflict.kt`
- Test: `backend/src/test/kotlin/vn/aiview/nvr/services/ChannelConflictTest.kt`

**Interfaces:**
- Consumes: `ChannelAllocator.hasExternalClaim/allocateLocal`, `Cameras.owner`
- Produces: `fun resolveCloudClaimConflict(channelId: String): ConflictOutcome`, `sealed class ConflictOutcome { object Free; data class Relocated(val slug: String, val from: String, val to: String); data class Refused(val slug: String, val issuedTo: String) }`

- [ ] **Step 1: Viết test**

`backend/src/test/kotlin/vn/aiview/nvr/services/ChannelConflictTest.kt`:

```kotlin
package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import vn.aiview.nvr.TestDb
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.models.IssuedTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelConflictTest {

    private fun cam(slug: String, ch: String, owner: String) = Cameras.insert {
        it[Cameras.slug] = slug
        it[Cameras.displayName] = slug
        it[Cameras.channelId] = ch
        it[Cameras.owner] = owner
        it[Cameras.uid] = slug
    }

    @Test
    fun `so trong thi Free`() = TestDb.withCleanDb {
        assertEquals(ConflictOutcome.Free, resolveCloudClaimConflict("1"))
    }

    @Test
    fun `camera local chua ra ngoai thi bi doi`() = TestDb.withCleanDb {
        cam("cam_l", "1", "local")
        val out = resolveCloudClaimConflict("1")
        assertTrue(out is ConflictOutcome.Relocated)
        out as ConflictOutcome.Relocated
        assertEquals("1", out.from)
        assertEquals(out.to, Cameras.selectAll().single()[Cameras.channelId])
    }

    @Test
    fun `camera local da tra cho Box thi tu choi`() = TestDb.withCleanDb {
        cam("cam_l", "1", "local")
        ChannelAllocator.claim("1", IssuedTo.BOX)
        val out = resolveCloudClaimConflict("1")
        assertTrue(out is ConflictOutcome.Refused)
        assertEquals("1", Cameras.selectAll().single()[Cameras.channelId])
    }

    @Test
    fun `camera cloud dang o so do thi Free (put lai chinh no)`() = TestDb.withCleanDb {
        cam("cam_c", "1", "cloud")
        assertEquals(ConflictOutcome.Free, resolveCloudClaimConflict("1"))
    }
}
```

- [ ] **Step 2: Chạy test, kỳ vọng FAIL**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.ChannelConflictTest"`
Expected: FAIL — `resolveCloudClaimConflict` chưa tồn tại.

- [ ] **Step 3: Viết**

`backend/src/main/kotlin/vn/aiview/nvr/services/ChannelConflict.kt`:

```kotlin
package vn.aiview.nvr.services

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import vn.aiview.nvr.models.Cameras
import vn.aiview.nvr.utils.AppLogger

sealed class ConflictOutcome {
    /** Số dùng được ngay. */
    object Free : ConflictOutcome()
    data class Relocated(val slug: String, val from: String, val to: String) : ConflictOutcome()
    data class Refused(val slug: String, val issuedTo: String) : ConflictOutcome()
}

/**
 * Lưới an toàn cho trường hợp cloud vẫn cấp một số mà camera local đang giữ.
 *
 * Không loại trừ được bằng trần `maxChannelNum`, vì không biết server hộp đen có tôn trọng
 * trường đó hay không.
 *
 * Hai nhánh, và nhánh "từ chối" là chỗ dễ làm sai nhất: dời một camera local đã được trả cho
 * Box sẽ khiến Box coi nó là camera MỚI và giữ luôn số cũ thành ma bên Box — ta đổi một cái
 * nói sai ở cloud thành một cái nói sai ở Box, không sửa được gì. Thà để cloud báo lỗi ngay
 * lúc người ta đang bấm thêm camera.
 */
fun resolveCloudClaimConflict(channelId: String): ConflictOutcome {
    val holder = Cameras.selectAll().firstOrNull { it[Cameras.channelId] == channelId }
        ?: return ConflictOutcome.Free
    if (holder[Cameras.owner] == "cloud") return ConflictOutcome.Free

    val slug = holder[Cameras.slug]
    if (ChannelAllocator.hasExternalClaim(channelId)) {
        AppLogger.warn(AppLogger.Module.CAMERA, "CHANNEL_ALLOC", "REFUSE_CLOUD_CLAIM",
            "channel=$channelId đang thuộc camera local $slug và ĐÃ giao ra ngoài — từ chối put_channels")
        return ConflictOutcome.Refused(slug, "box/bkav")
    }

    val newCh = ChannelAllocator.allocateLocal()
    Cameras.update({ Cameras.slug eq slug }) { it[Cameras.channelId] = newCh }
    AppLogger.warn(AppLogger.Module.CAMERA, "CHANNEL_ALLOC", "RELOCATE",
        "camera local $slug: channel $channelId→$newCh để nhường cho cloud")
    return ConflictOutcome.Relocated(slug, channelId, newCh)
}
```

- [ ] **Step 4: Chạy test, kỳ vọng PASS**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.ChannelConflictTest"`
Expected: PASS, 4 test.

- [ ] **Step 5: Móc vào `postOrPutChannels`**

Trong `CameraCommandHandler.postOrPutChannels`, sau chốt idempotent (Task 9) và **trước** `ChannelAllocator.claim`:

```kotlin
        val conflict = transaction { resolveCloudClaimConflict(channelId) }
        if (conflict is ConflictOutcome.Refused) {
            return CommandDispatcher.errorResponse("E0007",
                "Kênh $channelId đang được thiết bị khác (AiView Box) sử dụng. " +
                    "Chọn kênh khác, hoặc xoá camera đó trên NVR trước.")
        }
        if (conflict is ConflictOutcome.Relocated) {
            AppLogger.warn(AppLogger.Module.SYSTEM, "CLOUD", "LOCAL_CAM_RELOCATED",
                "slug=${conflict.slug} channel=${conflict.from}→${conflict.to}")
        }
```

Sau khi dời, camera local phải được đăng ký lại alias go2rtc theo số mới — gọi
`CameraService.reregisterChannelAliases(conflict.slug, conflict.from, conflict.to)`, hàm này
xoá `${from}_main`/`${from}_sub` và đăng ký `${to}_main`/`${to}_sub` trỏ vào
`rtsp://127.0.0.1:8554/<slug>`, dùng lại `Go2RtcClient.deleteStream`/`registerStreamSync` đang
có ở `CameraService.kt:197` và `:310`.

- [ ] **Step 6: Chạy toàn bộ test + build**

Run: `cd backend && ./gradlew test && ./gradlew installDist`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/vn/aiview/nvr backend/src/test
git commit -m "Feat(camera-identity): lưới an toàn khi cloud giành số camera local (dời hoặc từ chối)"
```

---

### Task 14: `IntegrationService.streamUrl` dùng slug (spec §5.1)

**Files:**
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/services/IntegrationService.kt:179-182`
- Modify: `backend/src/main/kotlin/vn/aiview/nvr/routes/IntegrationRouting.kt:175-176`
- Test: `backend/src/test/kotlin/vn/aiview/nvr/services/IntegrationStreamUrlTest.kt`

**Interfaces:**
- Consumes: `Cameras.slug`
- Produces: `fun IntegrationService.streamUrl(slug: String, sub: Boolean): String` — chữ ký đổi từ `channelId` sang `slug`

- [ ] **Step 1: Viết test**

`backend/src/test/kotlin/vn/aiview/nvr/services/IntegrationStreamUrlTest.kt`:

```kotlin
package vn.aiview.nvr.services

import kotlin.test.Test
import kotlin.test.assertTrue

class IntegrationStreamUrlTest {
    @Test
    fun `url main dung slug chu khong dung so kenh`() {
        val url = IntegrationService.streamUrl("cam_ab12cd34", sub = false)
        assertTrue(url.endsWith("/cam_ab12cd34"), "URL phải trỏ vào slug, thực tế: $url")
    }

    @Test
    fun `url sub dung slug_sub`() {
        val url = IntegrationService.streamUrl("cam_ab12cd34", sub = true)
        assertTrue(url.endsWith("/cam_ab12cd34_sub"), "URL sub phải là <slug>_sub, thực tế: $url")
    }
}
```

- [ ] **Step 2: Chạy test, kỳ vọng FAIL**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.IntegrationStreamUrlTest"`
Expected: FAIL — hiện `streamUrl` sinh `<channelId>_main`.

- [ ] **Step 3: Đổi `streamUrl`**

```kotlin
    /**
     * URL restream RTSP giao cho Box. Dùng `slug`, KHÔNG dùng `channel_id`.
     *
     * Hai lý do: (1) alias `${channelId}_main` trở lại thành cloud-only, nên một số kênh cũ
     * mà cloud còn nhớ không phân giải được sang stream của camera local — phòng thủ lớp hai
     * sau `CloudChannelResolver`; (2) URL không đứt khi camera bị dời số ở lưới an toàn
     * (`resolveCloudClaimConflict`), vì `slug` không bao giờ đổi.
     *
     * `camera_id` trả cho Box vẫn là `channel_id` — đó là hợp đồng của giao thức v1.2, không
     * đổi đơn phương được.
     */
    fun streamUrl(slug: String, sub: Boolean): String {
        val name = if (sub) "${slug}_sub" else slug
        return "rtsp://${lanIp()}:8554/$name"
    }
```

- [ ] **Step 4: Sửa call-site**

Trong `IntegrationRouting.kt`, trong `mapNotNull`, lấy thêm `val slug = row[Cameras.slug]` và đổi:

```kotlin
                                main_stream_url = IntegrationService.streamUrl(slug, sub = false),
                                sub_stream_url = if (hasSub) IntegrationService.streamUrl(slug, sub = true) else null,
```

- [ ] **Step 5: Chạy test, kỳ vọng PASS**

Run: `cd backend && ./gradlew test --tests "vn.aiview.nvr.services.IntegrationStreamUrlTest"`
Expected: PASS, 2 test.

- [ ] **Step 6: Kiểm stream tồn tại thật**

```bash
./run.sh --rebuild
SLUG=$(python3 -c "import sqlite3;print(list(sqlite3.connect('data/nvr.db').execute('select slug from cameras limit 1'))[0][0])")
curl -s "http://127.0.0.1:1984/api/streams" | python3 -m json.tool | grep -c "$SLUG"
```

Expected: ≥ 1 — go2rtc có stream tên đúng slug (đã có sẵn từ `CameraService`, không cần alias mới).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/vn/aiview/nvr backend/src/test
git commit -m "Refactor(box): URL restream giao cho Box dùng slug thay vì số kênh"
```

---

## Self-Review

**Spec coverage:**

| Spec | Task |
|---|---|
| §3 schema `uid`/`owner`/`channel_claims` | 2 |
| §3 slug từ uid | 5 |
| §4.1 trần động `maxChannelNum` | 7 |
| §4.2 claim khi ra khỏi máy | 6 |
| §4.2 orphan/released | 4, 10 |
| §4.3 lưới an toàn cloud giành số | **thiếu — xem dưới** |
| §4.4 một cổng duy nhất | 4, 5 |
| §5.1 `resolveCloudChannel` mọi cửa | 7 |
| §5.1 `streamUrl` dùng slug | **thiếu — xem dưới** |
| §5.2 Actor + khoá | 8 |
| §5.3 khoá cái gì | 8 |
| §5.4 ngoại lệ xoá gateway | 8 (Step 5) |
| §6.1 đường thoát | 10, 11 |
| §6.2 màn dọn | 10, 11 |
| §7.1 tên từ ONVIF | 9 |
| §7.2 put_channels idempotent | 9 |
| §7.3 gộp bộ sinh slug | 5 |
| §8 migration | 3 |
| §9 kiểm chứng | rải trong mọi task + 12 |

Hai lỗ hổng tìm ra khi tự soi, **đã bổ sung thành Task 13 và 14 dưới đây** chứ không để trong ghi chú.

**Placeholder scan:** không còn "TBD"/"tương tự Task N"/"thêm xử lý lỗi phù hợp". Mọi bước có code đều mang code thật, không có bước nào nói "giống Task N".

**Type consistency:** `ChannelAllocator.orphanIfIssued` (không phải `orphan`), `ChannelAllocator.claim(channelId, IssuedTo)`, `CloudChannelResolver.resolve/cloudChannels/denied`, `CameraService.assertMutable(slug, actor, field)`, `CameraService.slugForNewCamera(uid)`, `CameraService.defaultDisplayName(...)`, `releaseAllCloudCameras()`. Đã đối chiếu giữa các task.

---
