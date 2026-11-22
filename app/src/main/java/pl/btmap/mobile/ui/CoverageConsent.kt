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
import pl.btmap.mobile.i18n.tr

/** One-time question about sharing coverage measurements, with what is and is not sent. */
@Composable
fun CoverageConsentDialog(onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(tr("Pomagaj budować mapę zasięgu", "Help build the coverage map"), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    tr("BTMap może przesyłać anonimowe pomiary sygnału, z których powstaje wspólna mapa zasięgu na btmap.pl i w aplikacji.", "BTMap can send anonymous signal measurements that build a shared coverage map on btmap.pl and in the app."),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Text(tr("Wysyłamy: operatora, technologię, pasmo, siłę sygnału i miejsce pomiaru.", "We send: the operator, technology, band, signal strength and where it was measured."), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                Text(tr("Nie wysyłamy: numeru telefonu, ID urządzenia, konta ani tras — serwer zapisuje tylko liczniki w kwadratach ~100 m.", "We do not send: your phone number, device ID, account or routes — the server keeps only counters in ~100 m squares."), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
                Text(tr("Pomiary idą małymi paczkami (kilka kB) przez Wi-Fi lub dane komórkowe. Możesz to wyłączyć w Ustawienia → Pomiary.", "Measurements go in small batches (a few kB) over Wi-Fi or mobile data. You can turn this off in Settings → Measurements."), style = MaterialTheme.typography.bodySmall, color = Btmap.colors.muted)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(tr("Zgadzam się", "I agree"), color = Btmap.colors.accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(tr("Nie, dziękuję", "No, thanks")) } },
        containerColor = Btmap.colors.card,
    )
}
