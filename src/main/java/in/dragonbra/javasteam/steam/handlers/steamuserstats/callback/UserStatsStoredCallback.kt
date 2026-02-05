package `in`.dragonbra.javasteam.steam.handlers.steamuserstats.callback

import `in`.dragonbra.javasteam.base.ClientMsgProtobuf
import `in`.dragonbra.javasteam.base.IPacketMsg
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesClientserverUserstats.CMsgClientStoreUserStatsResponse
import `in`.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats
import `in`.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackMsg
import `in`.dragonbra.javasteam.util.JavaSteamAddition

/**
 * A stat that failed validation when storing; the server reverted it to the given value.
 */
data class StatsFailedValidation(val statId: Int, val revertedStatValue: Int)

/**
 * This callback is fired in response to [SteamUserStats.storeUserStats].
 */
@JavaSteamAddition
class UserStatsStoredCallback(packetMsg: IPacketMsg?) : CallbackMsg() {

    /**
     * Gets the result of the request.
     */
    val result: EResult

    /**
     * The game id of the stats.
     */
    val gameId: Long

    /**
     * The crc of the stats.
     */
    val crcStats: Int

    /**
     * Whether the stats were out of date on the server.
     */
    val statsOutOfDate: Boolean

    /**
     * Stats that failed validation; the server reverted each to [StatsFailedValidation.revertedStatValue].
     */
    val statsFailedValidation: List<StatsFailedValidation>

    init {
        val msg = ClientMsgProtobuf<CMsgClientStoreUserStatsResponse.Builder>(
            CMsgClientStoreUserStatsResponse::class.java,
            packetMsg
        )
        val resp = msg.body

        jobID = msg.targetJobID
        result = EResult.from(resp.eresult)

        gameId = resp.gameId
        crcStats = resp.crcStats
        statsOutOfDate = resp.statsOutOfDate

        statsFailedValidation = resp.statsFailedValidationList.map {
            StatsFailedValidation(
                statId = it.statId,
                revertedStatValue = it.revertedStatValue
            )
        }
    }
}
