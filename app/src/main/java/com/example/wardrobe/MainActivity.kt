package com.example.wardrobe

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wardrobe.data.AppDatabase
import com.example.wardrobe.ui.theme.Theme
import com.example.wardrobe.data.WardrobeRepository
import com.example.wardrobe.ui.components.MainView
import com.example.wardrobe.ui.theme.WardrobeTheme
import com.example.wardrobe.viewmodel.MemberViewModel
import com.example.wardrobe.viewmodel.WardrobeViewModel
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import com.example.wardrobe.viewmodel.MainViewModel

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var appRepository: WardrobeRepository
    private lateinit var mainVm: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = AppDatabase.get(this)
        appRepository = WardrobeRepository(db.clothesDao(), db.settingsRepository)

        // Factory for MemberViewModel (because it requires a custom constructor)
        val memberVmFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return MemberViewModel(appRepository) as T
            }
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            var theme by remember { mutableStateOf(Theme.LIGHT) }

            mainVm = viewModel()

            // ------------- NFC ReaderMode Setup --------------
            LaunchedEffect(Unit) {
                enableNfcReader()
            }

            WardrobeTheme(darkTheme = theme == Theme.DARK) {
                val memberViewModel: MemberViewModel = viewModel(factory = memberVmFactory)

                MainView(
                    repo = appRepository,
                    vm = memberViewModel,
                    theme = theme,
                    onThemeChange = { theme = it },
                    mainVm = viewModel()
                )
            }
        }
    }

    // -------------------- NFC Reader Mode ---------------------
    private fun enableNfcReader() {
        nfcAdapter?.enableReaderMode(
            this,
            this,
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_NFC_BARCODE or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onResume() {
        super.onResume()
        enableNfcReader()
    }

    // ---------------------- Tag Scan ----------------------
    override fun onTagDiscovered(tag: Tag?) {
        if (tag == null) return

        val idBytes = tag.id
        val tagId = idBytes.joinToString("") { "%02X".format(it) } // 转 HEX 字符串

        // Handle tagId in MainViewModel
        mainVm.onTagScanned(tagId) { scannedId ->
            appRepository.getLocationForTag(scannedId)?.locationId
        }
    }
}

// ------------------ ViewModel factory for WardrobeViewModel ------------------

class WardrobeViewModelFactory(private val repo: WardrobeRepository, private val memberId: Long)
    : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WardrobeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WardrobeViewModel(repo, memberId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun WardrobeApp(memberId: Long, onExit: () -> Unit) {
    val context = LocalContext.current
    val db = AppDatabase.get(context)
    val repo = WardrobeRepository(db.clothesDao(), db.settingsRepository)

    val vmFactory = WardrobeViewModelFactory(repo, memberId)
    val vm: WardrobeViewModel =
        viewModel(
            key = memberId.toString(),
            factory = vmFactory
        )

    var route by remember { mutableStateOf("home") }
    var currentClothingId by remember { mutableStateOf<Long?>(null) }

    // Custom back-button navigation logic depending on the current route
    BackHandler(enabled = true) {
        when (route) {
            "edit" -> route = if (currentClothingId != null) "detail" else "home"
            "detail" -> route = "home"
            "home" -> onExit()
        }
    }

    // Simple navigation state machine
    when (route) {
        "home" -> com.example.wardrobe.ui.HomeScreen(
            vm = vm,
            onAddClick = { currentClothingId = null; route = "edit" },
            onItemClick = { id -> currentClothingId = id; route = "detail" }
        )

        "detail" -> com.example.wardrobe.ui.ItemDetailScreen(
            vm = vm,
            itemId = currentClothingId ?: 0L,
            onBack = { route = "home" },
            onEdit = { route = "edit" }
        )

        "edit" -> com.example.wardrobe.ui.EditItemScreen(
            vm = vm,
            itemId = currentClothingId,
            onDone = { route = if (currentClothingId != null) "detail" else "home" }
        )
    }
}
