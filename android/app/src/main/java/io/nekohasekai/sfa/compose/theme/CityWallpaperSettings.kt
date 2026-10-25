package io.nekohasekai.sfa.compose.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R

@Composable
internal fun CityWallpaperSettings() {
    val context = LocalContext.current
    val cities = remember(context) { CityWallpapers.choices(context) }
    var choose by remember { mutableStateOf(false) }
    val selected = cities.find { it.id == CityWallpapers.selected } ?: cities.firstOrNull()
    ListItem(
        headlineContent = { Text(stringResource(R.string.city_wallpaper_city)) },
        supportingContent = { Text(selected?.let { "${it.city} · ${it.country}" }.orEmpty()) },
        modifier = Modifier.clickable { choose = true },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    if (choose) {
        var search by remember { mutableStateOf("") }
        KnineGlassAlertDialog(
            onDismissRequest = { choose = false },
            title = { Text(stringResource(R.string.city_wallpaper_city)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it }, singleLine = true,
                        label = { Text(stringResource(R.string.city_wallpaper_search)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        val query = search.trim()
                        val filtered = cities.filter { query.isEmpty() || "${it.city} ${it.country} ${it.title} ${it.id}".contains(query, ignoreCase = true) }
                        items(filtered, key = { it.id }) { city ->
                            ListItem(
                                headlineContent = { Text(city.city) },
                                supportingContent = { Text("${city.country} · ${city.title}", style = MaterialTheme.typography.bodySmall) },
                                modifier = Modifier.clickable { CityWallpapers.select(city.id); choose = false },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                        if (filtered.isEmpty()) item { Text(stringResource(R.string.city_wallpaper_empty)) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choose = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    ChoiceRow(
        stringResource(R.string.city_wallpaper_light), CityWallpapers.time,
        listOf("city" to stringResource(R.string.city_wallpaper_clock), "day" to stringResource(R.string.city_wallpaper_day), "night" to stringResource(R.string.city_wallpaper_night)),
        CityWallpapers::chooseTime,
    )
    ChoiceRow(
        stringResource(R.string.city_wallpaper_weather), CityWallpapers.weather,
        listOf("story" to stringResource(R.string.city_wallpaper_story), "clear" to stringResource(R.string.city_wallpaper_clear),
            "cloudy" to stringResource(R.string.city_wallpaper_cloudy), "shower" to stringResource(R.string.city_wallpaper_rain),
            "mist" to stringResource(R.string.city_wallpaper_mist), "snow" to stringResource(R.string.city_wallpaper_snow)),
        CityWallpapers::chooseWeather,
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.city_wallpaper_motion)) },
        supportingContent = { Text(stringResource(R.string.city_wallpaper_motion_detail)) },
        trailingContent = { Switch(checked = CityWallpapers.motion, onCheckedChange = CityWallpapers::chooseMotion) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ChoiceRow(title: String, value: String, options: List<Pair<String, String>>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(options.find { it.first == value }?.second.orEmpty()) },
            modifier = Modifier.clickable { expanded = true },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { select(id); expanded = false })
            }
        }
    }
}
