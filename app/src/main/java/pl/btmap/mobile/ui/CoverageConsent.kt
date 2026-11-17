package pl.btmap.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.btmap.mobile.ui.theme.Btmap

/** One-time question about sharing coverage measurements, with what is and is not sent. */
@Composable
fun CoverageConsentDialog(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Pomagaj budować mapę zasięgu", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "BTMap może przesyłać anonimowe pomiary sygnału, z których powstaje wspólna mapa zasięgu na btmap.pl i w aplikacji.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text("Wysyłamy: operatora, technologię, pasmo, siłę sygnału i miejsce pomiaru.", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                Text("Nie wysyłamy: numeru telefonu, ID urządzenia, konta ani tras — serwer zapisuje tylko liczniki w kwadratach ~100 m.", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                Text("Pomiary idą paczkami przez Wi-Fi. Możesz to wyłączyć w Ustawienia → Pomiary.", style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Zgadzam się", color = Btmap.colors.accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Nie, dziękuję") } },
        containerColor = Btmap.colors.card,
    )
}
