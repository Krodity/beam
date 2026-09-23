package uk.krodity.beam.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import uk.krodity.beam.data.PairLink

/** First-run pairing. Normally over in one scan: the broker's /pair page and the
 *  extension popup both draw a QR of the very same deep link that tapping the
 *  page would fire, so the two routes land in the same [PairLink] parser. */
@Composable
fun PairScreen(
    initialBroker: String,
    initialToken: String,
    onPair: (String, String) -> Unit,
) {
    var broker by rememberSaveable { mutableStateOf(initialBroker) }
    var token by rememberSaveable { mutableStateOf(initialToken) }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }

    // zxing's CaptureActivity asks for CAMERA itself; a refusal comes back as a
    // cancelled scan, which is also what backing out looks like — stay quiet.
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) return@rememberLauncherForActivityResult
        val pairing = PairLink.parse(contents)
        if (pairing == null) {
            scanError = "That code isn't a Beam pairing code."
        } else {
            scanError = null
            broker = pairing.broker
            token = pairing.token
            onPair(pairing.broker, pairing.token)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Cast, null,
            modifier = Modifier.size(44.dp).align(Alignment.CenterHorizontally),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Pair with your PC",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "On the PC, open the Beam extension popup (or http://<your-pc>:8780/pair) " +
                "and scan the code it shows.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                scanError = null
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Point at the pairing code on your PC")
                        .setBeepEnabled(false)
                        .setOrientationLocked(false)
                )
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scan QR code")
        }
        scanError?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "or enter it by hand",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = broker,
            onValueChange = { broker = it },
            label = { Text("Broker") },
            supportingText = { Text("Hostname or IP of the PC running beam-broker") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text("Pairing token") },
            supportingText = { Text("cat ~/.config/beam/token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = { onPair(broker, token) },
            enabled = broker.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Connect") }
    }
}
