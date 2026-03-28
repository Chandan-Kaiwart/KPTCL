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

    /**
     * Sirf us month ka cache invalidate karo jis par user abhi hai.
     * Baaki months ka data safe rahega (doosre ViewModel instances mein).
     * Cache month/year reset ho jaata hai taaki isCacheValid() false return kare
     * aur next load par fresh data fetch ho.
     */
    fun invalidateMonth(calendar: Calendar) {
        val targetMonth = calendar.get(Calendar.MONTH)
        val targetYear  = calendar.get(Calendar.YEAR)

        // Sirf tab clear karo jab cached month match kare
        if (cachedMonth == targetMonth && cachedYear == targetYear) {
            dayStatusMap.clear()
            cachedMonth = -1
            cachedYear  = -1
            // allFeeders ko mat clear karo — feeder list stable rehti hai
            // sirf status data re-fetch hoga, feeder list nahi
        }
    }

    /** Full reset — logout ya account change ke liye */
    fun invalidate() {
        allFeeders.clear()
        dayStatusMap.clear()
        cachedMonth = -1
        cachedYear = -1
    }
}