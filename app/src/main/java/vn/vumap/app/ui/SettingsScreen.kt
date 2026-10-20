package vn.vumap.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import vn.vumap.core.model.MapStyles
import vn.vumap.core.model.UserSettings
import vn.vumap.core.storage.SettingsRepository
import vn.vumap.feature.signs.ActivePackState
import vn.vumap.feature.signs.ActiveSignPackManager
import vn.vumap.feature.signs.BundledOfflinePackLoader
import vn.vumap.feature.signs.PackImportResult
import vn.vumap.feature.signs.SafPackImportHelper

/**
 * Settings screen: follow-mode, demo signs overlay, SAF sign pack update, map styles, privacy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepo: SettingsRepository,
    signPackManager: ActiveSignPackManager? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = UserSettings())

    val localManager = remember {
        if (signPackManager == null) BundledOfflinePackLoader.createActiveSignPackManager(context) else null
    }
    val activeManager = signPackManager ?: localManager
    DisposableEffect(localManager) {
        onDispose {
            localManager?.close()
        }
    }

    LaunchedEffect(activeManager) {
        activeManager?.initialize()
    }

    val packState by (activeManager?.activePackState?.collectAsStateWithLifecycle(initialValue = ActivePackState.Loading)
        ?: remember { mutableStateOf(ActivePackState.Loading) })

    var isImporting by remember { mutableStateOf(false) }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }
    var isImportSuccess by remember { mutableStateOf(false) }

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) {
            importStatusMessage = "Đã hủy chọn tệp"
            isImportSuccess = false
            return@rememberLauncherForActivityResult
        }
        val manager = activeManager ?: return@rememberLauncherForActivityResult
        scope.launch {
            isImporting = true
            importStatusMessage = null
            try {
                val result = SafPackImportHelper.importPackFromUris(context, manager, uris)
                when (result) {
                    is PackImportResult.Success -> {
                        isImportSuccess = true
                        importStatusMessage = "Nhập gói thành công: ${result.metadata.pointCount} biển báo (Thế hệ: ${result.generationId.take(16)})"
                    }
                    is PackImportResult.Failure -> {
                        isImportSuccess = false
                        importStatusMessage = "Nhập gói thất bại: ${result.reason}"
                    }
                }
            } catch (e: Exception) {
                isImportSuccess = false
                importStatusMessage = "Lỗi ngoại lệ khi nhập gói: ${e.message}"
            } finally {
                isImporting = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Tự động theo vị trí GPS", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.followLocation,
                    onCheckedChange = { scope.launch { settingsRepo.setFollowLocation(it) } },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text("Biển báo giao thông (Thử nghiệm)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hiển thị biển báo mẫu (Dữ liệu giả lập)")
                    Text(
                        "Bật lớp phủ biển báo từ gói ngoại tuyến mẫu. Dữ liệu giả lập chỉ dùng để thử nghiệm hiển thị, không dùng cho dẫn đường thực tế.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.demoSignsEnabled,
                    onCheckedChange = { scope.launch { settingsRepo.setDemoSignsEnabled(it) } },
                )
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "DỮ LIỆU GIẢ LẬP / THỬ NGHIỆM",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))

                    when (val current = packState) {
                        is ActivePackState.Loading -> {
                            Text("Đang kiểm tra gói ngoại tuyến...", style = MaterialTheme.typography.bodySmall)
                        }
                        is ActivePackState.Bundled -> {
                            Text("Gói đang hoạt động: Mặc định tích hợp (Bundled)", style = MaterialTheme.typography.bodyMedium)
                            Text("Nguồn: ${current.metadata.source}", style = MaterialTheme.typography.bodySmall)
                            Text("Phiên bản: ${current.metadata.version} | Biển báo: ${current.metadata.pointCount}", style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256: ${current.metadata.sha256.take(16)}...", style = MaterialTheme.typography.bodySmall)
                        }
                        is ActivePackState.Imported -> {
                            Text("Gói đang hoạt động: Cập nhật ngoại tuyến (Imported)", style = MaterialTheme.typography.bodyMedium)
                            Text("Thế hệ: ${current.pointer.generationId.take(20)}", style = MaterialTheme.typography.bodySmall)
                            Text("Nguồn: ${current.pointer.source} | Phiên bản: ${current.pointer.version}", style = MaterialTheme.typography.bodySmall)
                            Text("Biển báo: ${current.pointer.pointCount} | Đoạn đường: ${current.pointer.edgeCount}", style = MaterialTheme.typography.bodySmall)
                            Text("SHA-256: ${current.pointer.sha256.take(16)}...", style = MaterialTheme.typography.bodySmall)
                        }
                        is ActivePackState.Unavailable -> {
                            Text("Gói không khả dụng: ${current.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }

                    if (importStatusMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            importStatusMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isImportSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                safLauncher.launch(arrayOf("*/*"))
                            },
                            enabled = !isImporting && activeManager != null
                        ) {
                            if (isImporting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Đang nhập...")
                            } else {
                                Text("Cập nhật gói (.vupack + .json)")
                            }
                        }

                        if (packState is ActivePackState.Imported) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val resetOk = activeManager?.resetToBundled() == true
                                        if (resetOk) {
                                            importStatusMessage = "Đã khôi phục gói mặc định tích hợp"
                                            isImportSuccess = true
                                        } else {
                                            importStatusMessage = "Khôi phục gói mặc định thất bại — xem trạng thái gói hiện tại phía trên"
                                            isImportSuccess = false
                                        }
                                    }
                                },
                                enabled = !isImporting && activeManager != null
                            ) {
                                Text("Khôi phục mặc định")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Kiểu bản đồ (trực tuyến)", style = MaterialTheme.typography.titleSmall)
            MapStyles.ALL.forEach { (label, url) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.styleUrl == url,
                        onClick = { scope.launch { settingsRepo.setStyleUrl(url) } },
                    )
                    Text(label)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Quyền riêng tư", style = MaterialTheme.typography.titleSmall)
            Text(
                "VUMAP P0 không thu thập, không gửi telemetry. " +
                    "Bản đồ tải trực tuyến từ OpenFreeMap; vị trí GPS chỉ dùng trên máy. " +
                    "Tệp gói ngoại tuyến được lưu trữ trong thư mục riêng của ứng dụng.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
