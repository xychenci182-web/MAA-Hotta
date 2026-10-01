package com.aliothmoon.maahotta.data

import kotlinx.serialization.Serializable

@Serializable
data class GameAccount(
    val id: String,
    val label: String,
    val username: String,
    val password: String,
    val enabled: Boolean = true,
    val serverName: String = "",
    val characterName: String = "",
    val islandMerchantPending: Boolean = false,
)

@Serializable
enum class TrialType {
    WEAPON,
    MATRIX,
    GOLD,
}

@Serializable
enum class DailyTask {
    CHECK_IN,
    SUPPLY,
    MAIL,
    KITCHEN,
    TRIALS,
    ISLAND_MERCHANT,
    BYGONE_PHANTASM,
    GUILD_DONATE,
}

val defaultDailyTaskOrder: List<DailyTask> = listOf(
    DailyTask.CHECK_IN,
    DailyTask.SUPPLY,
    DailyTask.KITCHEN,
    DailyTask.TRIALS,
    DailyTask.ISLAND_MERCHANT,
    DailyTask.BYGONE_PHANTASM,
    DailyTask.MAIL,
    DailyTask.GUILD_DONATE,
)

@Serializable
data class TaskOptions(
    val login: Boolean = true,
    val kitchen: Boolean = true,
    val trials: Boolean = true,
    val islandMerchant: Boolean = true,
    val bygonePhantasm: Boolean = true,
    val guildDonate: Boolean = true,
    val guildRewards: Boolean = true,
    val guildWeeklyBenefit: Boolean = true,
    val mail: Boolean = true,
    val checkIn: Boolean = true,
    val supply: Boolean = true,
    val trialType: TrialType = TrialType.WEAPON,
    val kitchenWeekdays: Set<Int> = allWeekdays,
    val trialsWeekdays: Set<Int> = allWeekdays,
    val bygoneWeekdays: Set<Int> = allWeekdays,
    val mailWeekdays: Set<Int> = allWeekdays,
    val taskOrder: List<DailyTask> = defaultDailyTaskOrder,
)

@Serializable
data class AutoStartSchedule(
    val enabled: Boolean = false,
    val hour: Int = 5,
    val minute: Int = 10,
)

val allWeekdays: Set<Int> = (1..7).toSet()

fun TaskOptions.orderedDailyTasks(): List<DailyTask> =
    (taskOrder + defaultDailyTaskOrder).distinct()

fun TaskOptions.isEnabled(task: DailyTask): Boolean = when (task) {
    DailyTask.CHECK_IN -> checkIn
    DailyTask.SUPPLY -> supply
    DailyTask.MAIL -> mail
    DailyTask.KITCHEN -> kitchen
    DailyTask.TRIALS -> trials
    DailyTask.ISLAND_MERCHANT -> islandMerchant
    DailyTask.BYGONE_PHANTASM -> bygonePhantasm
    DailyTask.GUILD_DONATE -> guildDonate
}

@Serializable
data class AppConfig(
    val accounts: List<GameAccount> = emptyList(),
    val options: TaskOptions = TaskOptions(),
    val autoStartSchedule: AutoStartSchedule = AutoStartSchedule(),
    val keepAliveEnabled: Boolean = true,
    val barkPushEnabled: Boolean = false,
    val barkDeviceKey: String = "",
    val updateUrl: String = "",
    val stopOnFailure: Boolean = false,
    val bundledAccountsVersion: Int = 0,
)
