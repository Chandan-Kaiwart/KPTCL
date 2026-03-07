package com.apc.kptcl.home.users.dashboard

import androidx.lifecycle.ViewModel
import java.util.Calendar

class FeederStatusViewModel : ViewModel() {

    // Cache store
    var allFeeders: MutableList<FeederItem> = mutableListOf()
    val dayStatusMap: MutableMap<String, DayStatusInfo> = mutableMapOf()

    // Kis month ka cache hai
    var cachedMonth: Int = -1
    var cachedYear: Int = -1

    fun isCacheValid(calendar: Calendar): Boolean {
        return allFeeders.isNotEmpty() &&
                cachedMonth == calendar.get(Calendar.MONTH) &&
                cachedYear == calendar.get(Calendar.YEAR)
    }

    fun saveCache(
        feeders: List<FeederItem>,
        statusMap: Map<String, DayStatusInfo>,
        calendar: Calendar
    ) {
        allFeeders = feeders.toMutableList()
        dayStatusMap.clear()
        dayStatusMap.putAll(statusMap)
        cachedMonth = calendar.get(Calendar.MONTH)
        cachedYear = calendar.get(Calendar.YEAR)
    }

    fun invalidate() {
        allFeeders.clear()
        dayStatusMap.clear()
        cachedMonth = -1
        cachedYear = -1
    }
}