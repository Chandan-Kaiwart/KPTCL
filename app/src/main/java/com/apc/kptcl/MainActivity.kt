package com.apc.kptcl

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.apc.kptcl.databinding.ActivityMainBinding
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.navigation.navOptions
import com.apc.kptcl.utils.SessionManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var toggle: ActionBarDrawerToggle

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Enable edge-to-edge display and handle status bar properly
        enableEdgeToEdge()

        binding.toolbar.visibility = View.VISIBLE

        setupNavigation()
        setupNavigationDrawer()
        handleDrawerVisibility()
        setupBackPressHandler()
    }

    /**
     * Enable edge-to-edge display and set proper window insets
     */
    private fun enableEdgeToEdge() {
        // Make status bar transparent
        window.statusBarColor = android.graphics.Color.TRANSPARENT

        // Set status bar icons to dark (visible on light backgrounds)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Check session and set start destination
        checkAndSetupSession()
    }

    private fun checkAndSetupSession() {
        // Check if user is already logged in
        val isLoggedIn = SessionManager.isLoggedIn(this)

        Log.d(TAG, "Session check - Is Logged In: $isLoggedIn")

        if (isLoggedIn) {
            val username = SessionManager.getUsername(this)
            Log.d(TAG, "User session found: $username")

            // Don't change start destination here
            // Let LoginFragment handle the navigation to WelcomeFragment
            // This allows proper flow: Login -> Welcome -> Home
        } else {
            Log.d(TAG, "No active session - Showing login screen")
            // User is not logged in, default start destination (loginFragment) will be used
        }
    }

    private fun setupNavigationDrawer() {
        setSupportActionBar(binding.toolbar)

        // IMPORTANT: Set white background explicitly
        binding.navigationView.setBackgroundColor(
            ContextCompat.getColor(this, android.R.color.white)
        )

        // Define top-level destinations (fragments where drawer is visible)
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.homeFragment,
                R.id.feederViewFragment,
                R.id.viewTicketsFragment,
                R.id.consumptionEntryFragment,
                R.id.elogEntryFragment,
                R.id.feederHourlyEntryFragment,
                R.id.generalTicketFragment,
                R.id.stationTicketFragment
            ),
            binding.drawerLayout
        )

        setupActionBarWithNavController(navController, appBarConfiguration)

        // Setup drawer toggle with custom hamburger icon
        toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )

        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // Make hamburger icon more visible and larger
        customizeHamburgerIcon()

        // Update navigation header with user info
        updateNavigationHeader()
        colorDrawerHeadings()

        binding.navigationView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_home -> {
                    navController.navigate(R.id.homeFragment)
                }
                R.id.hourlyEntry -> {
                    navController.navigate(R.id.feederHourlyEntryFragment, null,
                        navOptions {
                            popUpTo(R.id.homeFragment) {
                                inclusive = false
                            }
                        })
                }
                R.id.hourlyView -> {
                    navController.navigate(R.id.feederViewFragment, null,
                        navOptions {
                            popUpTo(R.id.homeFragment) {
                                inclusive = false
                            }
                        })
                }
//                R.id.hourlyEdit -> {
//                    navController.navigate(R.id.feederEditFragment, null,
//                        navOptions {
//                            popUpTo(R.id.homeFragment) {
//                                inclusive = false
//                            }
//                        })
//                }
                R.id.dailyEntry -> {
                    navController.navigate(R.id.consumptionEntryFragment, null,
                        navOptions {
                            popUpTo(R.id.homeFragment) {
                                inclusive = false
                            }
                        })
                }
//                R.id.dailyEdit -> {
//                    navController.navigate(R.id.elogEntryFragment, null,
//                        navOptions {
//                            popUpTo(R.id.homeFragment) {
//                                inclusive = false
//                            }
//                        })
//                }
                R.id.dailyView -> {
                    navController.navigate(R.id.dailyParameterEntryFragment, null,
                        navOptions {
                            popUpTo(R.id.homeFragment) {
                                inclusive = false
                            }
                        })
                }
                R.id.createTicket -> {
                    // ✅ Navigate to GeneralTicketFragment
                    navController.navigate(R.id.generalTicketFragment, null,
                        navOptions {
                            popUpTo(R.id.homeFragment) {
                                inclusive = false
                            }
                        })
                }
                R.id.createStationTicket -> {
                    // ✅ Navigate to StationTicketFragment
                    navController.navigate(R.id.stationTicketFragment, null,
                        navOptions {
                            popUpTo(R.id.homeFragment) {
                                inclusive = false
                            }
                        })
                }
                R.id.viewTickets -> {
                    navController.navigate(R.id.viewTicketsFragment, null,
                        navOptions {
                            popUpTo(R.id.homeFragment) {
                                inclusive = false
                            }
                        })

                }
                R.id.excel -> {
                    navController.navigate(R.id.excelDownload, null,
                        navOptions {
                            popUpTo(R.id.homeFragment) {
                                inclusive = false
                            }
                        })
                }
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun colorDrawerHeadings() {
        val menu = binding.navigationView.menu

        fun setHeaderColor(index: Int, title: String) {
            val span = android.text.SpannableString(title).apply {
                setSpan(
                    android.text.style.ForegroundColorSpan(
                        ContextCompat.getColor(this@MainActivity, R.color.black)
                    ),
                    0,
                    length,
                    android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE
                )
                setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    0,
                    length,
                    android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE
                )
            }
            menu.getItem(index).title = span
        }

        // Index based on your menu.xml order
        setHeaderColor(1, "FEEDER HOURLY PARAMETER")
        setHeaderColor(2, "FEEDER DAILY PERFORMANCE")
        setHeaderColor(3, "TICKETS")
    }

    private fun customizeHamburgerIcon() {
        // Set custom hamburger icon color to make it more visible
        val icon = toggle.drawerArrowDrawable
        icon.color = ContextCompat.getColor(this, android.R.color.white)

        // Properly set content insets for better spacing
        // This ensures the hamburger icon has proper margins from screen edges
        binding.toolbar.setContentInsetsAbsolute(16, 0)
    }

    private fun updateNavigationHeader() {
        // Get header view
        val headerView = binding.navigationView.getHeaderView(0)

        // Find views in header
        val tvUsername = headerView.findViewById<android.widget.TextView>(R.id.tvHeaderUsername)
        val tvInfo = headerView.findViewById<android.widget.TextView>(R.id.tvHeaderInfo)

        // Get user data from SessionManager
        if (SessionManager.isLoggedIn(this)) {
            val username = SessionManager.getUsername(this)
            val escom = SessionManager.getEscom(this)
            val stationName = SessionManager.getStationName(this)

            tvUsername?.text = username
            tvInfo?.text = "$escom - $stationName"
        }
    }

    // ❌ Removed showLogoutConfirmation() - Now in HomeFragment
    // ❌ Removed performLogout() - Now in HomeFragment

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    }
                    navController.currentDestination?.id == R.id.homeFragment -> {
                        // If already on home, show exit confirmation
                        showExitConfirmation()
                    }
                    navController.currentDestination?.id == R.id.loginFragment -> {
                        // If on login screen, exit app
                        finish()
                    }
                    else -> {
                        // For any other fragment, navigate to home
                        navController.navigate(R.id.homeFragment, null,
                            navOptions {
                                popUpTo(R.id.homeFragment) {
                                    inclusive = true
                                }
                            })
                    }
                }
            }
        })
    }

    private fun showExitConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Exit App")
            .setMessage("Do you want to exit the application?")
            .setPositiveButton("Yes") { _, _ ->
                finish()
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun handleDrawerVisibility() {
        // Listen to navigation changes
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.loginFragment, R.id.welcomeFragment -> {
                    // Hide toolbar and disable drawer for login and welcome screens
                    binding.toolbar.visibility = View.GONE
                    binding.drawerLayout.setDrawerLockMode(
                        androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                    )
                }
                else -> {
                    // Show toolbar for all other screens
                    binding.toolbar.visibility = View.VISIBLE

                    // ✅ Check if DCC user - don't unlock drawer automatically
                    // Let HomeFragment handle drawer state for DCC users
                    if (!isDCCUserLoggedIn()) {
                        binding.drawerLayout.setDrawerLockMode(
                            androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
                        )

                        // Reapply hamburger icon customization
                        customizeHamburgerIcon()
                    }

                    // Update header whenever we navigate to a screen with drawer visible
                    updateNavigationHeader()

                    // IMPORTANT: Ensure white background remains after navigation
                    binding.navigationView.setBackgroundColor(
                        ContextCompat.getColor(this, android.R.color.white)
                    )
                    colorDrawerHeadings()
                }
            }
        }
    }

    /**
     * ✅ Check if DCC user is logged in
     */
    private fun isDCCUserLoggedIn(): Boolean {
        return try {
            val token = SessionManager.getToken(this)
            if (token.isEmpty()) return false

            val payload = com.apc.kptcl.utils.JWTUtils.decodeToken(token)
            payload?.role?.lowercase() == "dcc"
        } catch (e: Exception) {
            Log.e(TAG, "Error checking DCC user", e)
            false
        }
    }

    /**
     * ✅ Lock drawer (called from HomeFragment for DCC users)
     */
    fun lockDrawer() {
        binding.drawerLayout.setDrawerLockMode(
            androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED
        )
        // Hide hamburger icon
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        toggle.isDrawerIndicatorEnabled = false
    }

    /**
     * ✅ Unlock drawer (if needed later)
     */
    fun unlockDrawer() {
        binding.drawerLayout.setDrawerLockMode(
            androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED
        )
        // Show hamburger icon
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toggle.isDrawerIndicatorEnabled = true
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}