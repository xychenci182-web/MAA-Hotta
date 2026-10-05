package com.aliothmoon.maahotta.tasks

import com.aliothmoon.maahotta.data.DailyTask
import com.aliothmoon.maahotta.data.GameAccount
import com.aliothmoon.maahotta.data.TaskOptions
import com.aliothmoon.maahotta.data.isEnabled
import com.aliothmoon.maahotta.data.orderedDailyTasks
import com.aliothmoon.maahotta.engine.GameTask

fun buildDailyTasks(
    account: GameAccount,
    options: TaskOptions,
    includeLogin: Boolean = true,
    savedAccountPhones: List<String> = listOf(account.username),
    onIslandMerchantDetected: suspend (Boolean) -> Unit = {},
): List<GameTask> {
    val tasks = mutableListOf<GameTask>()
    if (options.login && includeLogin) tasks += LoginTask(account, savedAccountPhones = savedAccountPhones)
    val ordered = options.orderedDailyTasks().filter(options::isEnabled)
    ordered.forEachIndexed { index, task ->
        when (task) {
            DailyTask.CHECK_IN -> tasks += CheckInTask(
                keepWelfareOpenForSupply = ordered.getOrNull(index + 1) == DailyTask.SUPPLY,
            )
            DailyTask.SUPPLY -> tasks += SupplyTask()
            DailyTask.MAIL -> tasks += WeekdayTask(
                MailTask(),
                options.mailWeekdays,
            )
            DailyTask.KITCHEN -> tasks += WeekdayTask(
                KitchenTask(),
                options.kitchenWeekdays,
            )
            DailyTask.TRIALS -> tasks += WeekdayTask(
                TrialsTask(options.trialType),
                options.trialsWeekdays,
            )
            DailyTask.ISLAND_MERCHANT -> tasks += IslandMerchantTask(
                accountId = account.characterName.ifBlank { account.label },
                onDetected = onIslandMerchantDetected,
            )
            DailyTask.BYGONE_PHANTASM -> tasks += WeekdayTask(
                BygonePhantasmTask(),
                options.bygoneWeekdays,
            )
            DailyTask.GUILD_DONATE -> {
                tasks += GuildDonateTask(
                    keepGuildOpenForRewards = options.guildWeeklyBenefit || options.guildRewards,
                )
                if (options.guildWeeklyBenefit) {
                    tasks += GuildWeeklyBenefitTask(keepGuildOpenForRewards = options.guildRewards)
                }
                if (options.guildRewards) tasks += GuildRewardTask()
            }
        }
    }
    return tasks
}
