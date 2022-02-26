package net.adoptium.marketplace.dataSources.persitence.mongo

import com.mongodb.client.model.UpdateOptions
import kotlinx.coroutines.runBlocking
import net.adoptium.marketplace.dataSources.ReleaseInfo
import net.adoptium.marketplace.dataSources.TimeSource
import net.adoptium.marketplace.dataSources.persitence.VendorPersistence
import net.adoptium.marketplace.schema.Release
import net.adoptium.marketplace.schema.ReleaseList
import net.adoptium.marketplace.schema.ReleaseUpdateInfo
import net.adoptium.marketplace.schema.Vendor
import net.adoptium.marketplace.schema.VersionData
import org.bson.BsonBoolean
import org.bson.BsonDateTime
import org.bson.BsonDocument
import org.bson.BsonElement
import org.bson.BsonInt32
import org.bson.BsonString
import org.bson.Document
import org.litote.kmongo.EMPTY_BSON
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.util.KMongoUtil
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*

open class MongoVendorPersistence constructor(
    mongoClient: MongoClient,
    private val vendor: Vendor
) : MongoInterface(mongoClient), VendorPersistence {

    private val releasesCollection: CoroutineCollection<Release> = initDb(database, vendor.name + "_" + RELEASE_DB)
    private val releaseInfoCollection: CoroutineCollection<ReleaseInfo> = initDb(database, vendor.name + "_" + RELEASE_INFO_DB)
    private val updateTimeCollection: CoroutineCollection<UpdatedInfo> = initDb(database, vendor.name + "_" + UPDATE_TIME_DB, MongoVendorPersistence::initUptimeDb)
    private val updateLogCollection: CoroutineCollection<ReleaseUpdateInfo> = initDb(database, vendor.name + "_" + UPDATE_LOG)


    companion object {
        @JvmStatic
        private val LOGGER = LoggerFactory.getLogger(this::class.java)

        const val RELEASE_DB = "release"
        const val RELEASE_INFO_DB = "releaseInfo"
        const val UPDATE_TIME_DB = "updateTime"
        const val UPDATE_LOG = "updateLog"

        fun initUptimeDb(collection: CoroutineCollection<UpdatedInfo>) {
            runBlocking {
                try {
                    val set = KMongoUtil.filterIdToBson(UpdatedInfo(Date.from(TimeSource.now().minusMinutes(5).toInstant())))
                    collection.insertOne(UpdatedInfo(Date.from(TimeSource.now().minusMinutes(5).toInstant())))
                    set.toString()
                } catch (e: Exception) {
                    LOGGER.error("Failed to run init", e)
                }
            }
        }
    }

    override suspend fun writeReleases(releases: ReleaseList): ReleaseUpdateInfo {

        val added = mutableListOf<Release>()
        val updated = mutableListOf<Release>()

        releases
            .releases
            .filter { it.vendor == vendor }
            .forEach { release ->

                val matcher = releaseMatcher(release)

                val result = releasesCollection
                    .updateOne(matcher, release, UpdateOptions().upsert(true))

                if (result.upsertedId != null) {
                    added.add(release)
                } else {
                    if (result.modifiedCount > 0) {
                        updated.add(release)
                    }
                }
            }

        val currentDb = this.getAllReleases()

        val removed = currentDb
            .releases
            .filter { currentRelease ->
                releases.releases.none {
                    it.vendor == currentRelease.vendor &&
                        it.release_name == currentRelease.release_name &&
                        it.release_link == currentRelease.release_link &&
                        it.version_data.compareTo(currentRelease.version_data) == 0
                }
            }
            .map { toRemove ->
                LOGGER.info("Removing old release ${toRemove.release_name}")
                val matcher = releaseMatcher(toRemove)
                val deleted = releasesCollection.deleteMany(matcher)
                if (deleted.deletedCount != 1L) {
                    LOGGER.error("Failed to delete release ${toRemove.release_name}")
                }
                return@map toRemove
            }

        if (added.isNotEmpty() || updated.isNotEmpty() || removed.isNotEmpty()) {
            updateUpdatedTime(Date())
        }

        val result = ReleaseUpdateInfo(ReleaseList(added), ReleaseList(updated), ReleaseList(removed), Date())
        logUpdate(result)
        return result
    }

    private suspend fun logUpdate(result: ReleaseUpdateInfo) {
        updateLogCollection.insertOne(result)
        updateTimeCollection.deleteMany(Document("timestamp", BsonDocument("\$lt", BsonDateTime(result.timestamp.toInstant().minus(Duration.ofDays(30)).toEpochMilli()))))
    }

    override suspend fun setReleaseInfo(releaseInfo: ReleaseInfo) {
        releaseInfoCollection.deleteMany(releaseVersionDbEntryMatcher())
        releaseInfoCollection.updateOne(
            releaseVersionDbEntryMatcher(),
            releaseInfo,
            UpdateOptions().upsert(true)
        )
    }

    private suspend fun updateUpdatedTime(dateTime: Date) {
        updateTimeCollection.updateOne(
            Document(),
            UpdatedInfo(dateTime),
            UpdateOptions().upsert(true)
        )
        updateTimeCollection.deleteMany(Document("time", BsonDocument("\$lt", BsonDateTime(dateTime.toInstant().toEpochMilli()))))
    }

    override suspend fun getUpdatedInfoIfUpdatedSince(since: Date): UpdatedInfo? {
        val result = updateTimeCollection.find(Document("time", BsonDocument("\$gt", BsonDateTime(since.toInstant().toEpochMilli()))))

        return result.first()
    }

    override suspend fun getReleaseVendorStatus(): List<ReleaseUpdateInfo> {
        return updateLogCollection.find(EMPTY_BSON).toList()
    }

    override suspend fun getReleaseInfo(): ReleaseInfo? {
        return releaseInfoCollection.findOne(releaseVersionDbEntryMatcher())
    }

    override suspend fun getAllReleases(): ReleaseList {
        return ReleaseList(releasesCollection.find(EMPTY_BSON).toList())
    }

    private fun releaseVersionDbEntryMatcher() = Document("tip_version", BsonDocument("\$exists", BsonBoolean(true)))

    private fun releaseMatcher(release: Release): BsonDocument {
        return BsonDocument(
            listOf(
                BsonElement("release_name", BsonString(release.release_name)),
                BsonElement("release_link", BsonString(release.release_link)),
                BsonElement("vendor", BsonString(release.vendor.name))
            )
                .plus(versionMatcher(release.version_data))
        )
    }

    private fun versionMatcher(versionData: VersionData): List<BsonElement> {
        var matcher = listOf(
            BsonElement("version_data.openjdk_version", BsonString(versionData.openjdk_version)),
            BsonElement("version_data.major", BsonInt32(versionData.major))
        )

        if (versionData.build.isPresent) {
            matcher = matcher.plus(BsonElement("version_data.build", BsonInt32(versionData.build.get())))
        }
        if (versionData.minor.isPresent) {
            matcher = matcher.plus(BsonElement("version_data.minor", BsonInt32(versionData.minor.get())))
        }
        if (versionData.pre.isPresent) {
            matcher = matcher.plus(BsonElement("version_data.pre", BsonString(versionData.pre.get())))
        }
        if (versionData.optional.isPresent) {
            matcher = matcher.plus(BsonElement("version_data.optional", BsonString(versionData.optional.get())))
        }
        if (versionData.patch.isPresent) {
            matcher = matcher.plus(BsonElement("version_data.patch", BsonInt32(versionData.patch.get())))
        }
        if (versionData.security.isPresent) {
            matcher = matcher.plus(BsonElement("version_data.security", BsonInt32(versionData.security.get())))
        }

        return matcher
    }
}
