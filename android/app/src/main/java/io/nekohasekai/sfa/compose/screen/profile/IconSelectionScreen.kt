package io.nekohasekai.sfa.compose.screen.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.nekohasekai.sfa.compose.screen.dashboard.AnimatedCheck
import io.nekohasekai.sfa.compose.screen.dashboard.FitText
import io.nekohasekai.sfa.compose.theme.KnineMotion
import io.nekohasekai.sfa.compose.theme.LocalTransparentUi
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import cc.txwd.kone.shared.Countries
import cc.txwd.kone.shared.Country
import io.nekohasekai.sfa.compose.util.FlagGlyph
import io.nekohasekai.sfa.compose.util.FlagIcons
import io.nekohasekai.sfa.compose.util.ProfileIcon
import io.nekohasekai.sfa.compose.util.icons.IconCategory
import io.nekohasekai.sfa.compose.util.icons.MaterialIconsLibrary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconSelectionScreen(currentIconId: String?, onIconSelected: (String?) -> Unit, onNavigateBack: () -> Unit) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(IconViewMode.CATEGORIES) }
    var isSearchActive by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Get icons based on current mode
    val displayedIcons =
        remember(searchQuery, selectedCategory, viewMode) {
            when {
                searchQuery.isNotEmpty() -> MaterialIconsLibrary.searchIcons(searchQuery)
                selectedCategory != null -> {
                    MaterialIconsLibrary.categories
                        .find { it.name == selectedCategory }
                        ?.icons ?: emptyList()
                }
                viewMode == IconViewMode.ALL -> MaterialIconsLibrary.getAllIcons()
                else -> emptyList()
            }
        }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.select_icon)) },
            navigationIcon = {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
            actions = {
                IconButton(
                    onClick = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) {
                            searchQuery = ""
                            viewMode = IconViewMode.CATEGORIES
                            selectedCategory = null
                            focusManager.clearFocus()
                        }
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription =
                        if (isSearchActive) {
                            stringResource(R.string.close_search)
                        } else {
                            stringResource(
                                R.string.search_icons,
                            )
                        },
                        tint = LocalContentColor.current.copy(alpha = if (isSearchActive) 1f else 0.75f),
                    )
                }
            },
            colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }

    val currentIcon =
        currentIconId?.let { id ->
            MaterialIconsLibrary.getIconById(id)?.let { icon -> id to icon }
        }
    val currentFlag = FlagIcons.codeOf(currentIconId)?.let { Countries.byCode(it) }
    val flagResults = remember(searchQuery) { if (searchQuery.isEmpty()) emptyList() else Countries.search(searchQuery) }
    val bottomBarPadding =
        if (currentIcon != null || currentFlag != null) {
            88.dp
        } else {
            0.dp
        }

    Box(modifier = Modifier.fillMaxSize()) {
        val scaffoldPadding = LocalScaffoldPadding.current

        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(bottom = bottomBarPadding),
        ) {
            // Show search bar with animation
            AnimatedVisibility(visible = isSearchActive, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                // The field alone, on the city like every other field; no raised bar behind it.
                Box(Modifier.fillMaxWidth()) {
                    val focusRequester = remember { FocusRequester() }

                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            if (it.isNotEmpty()) {
                                viewMode = IconViewMode.SEARCH
                            } else {
                                viewMode = IconViewMode.CATEGORIES
                                selectedCategory = null
                            }
                        },
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                            .focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.search_icons_placeholder)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search),
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    viewMode = IconViewMode.CATEGORIES
                                    selectedCategory = null
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = stringResource(R.string.content_description_clear_search),
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions =
                        KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
                            },
                        ),
                    )
                }
            }

            Column(
                modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                // View mode tabs (only show when not searching)
                AnimatedVisibility(visible = searchQuery.isEmpty(), enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CategoryChip(stringResource(R.string.categories), null, chosen = viewMode == IconViewMode.CATEGORIES && selectedCategory == null) {
                            viewMode = IconViewMode.CATEGORIES
                            selectedCategory = null
                        }
                        CategoryChip(stringResource(R.string.all_icons), null, chosen = viewMode == IconViewMode.ALL) {
                            viewMode = IconViewMode.ALL
                            selectedCategory = null
                        }
                        CategoryChip(stringResource(R.string.kone_flags), null, chosen = viewMode == IconViewMode.FLAGS) {
                            viewMode = IconViewMode.FLAGS
                            selectedCategory = null
                        }
                        CategoryChip(stringResource(R.string.default_text), null, chosen = currentIconId == null) {
                            onIconSelected(null)
                            onNavigateBack()
                        }
                    }
                }

                // Back button when category is selected
                AnimatedVisibility(visible = selectedCategory != null && searchQuery.isEmpty(), enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                    TextButton(
                        onClick = {
                            selectedCategory = null
                            viewMode = IconViewMode.CATEGORIES
                        },
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.back_to_categories))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Main content area
                Box(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when {
                        // Search results
                        searchQuery.isNotEmpty() -> {
                            if (displayedIcons.isEmpty() && flagResults.isEmpty()) {
                                EmptySearchResult(searchQuery)
                            } else {
                                IconGrid(
                                    icons = displayedIcons,
                                    currentIconId = currentIconId,
                                    flags = flagResults,
                                    onFlagClick = { country ->
                                        onIconSelected(FlagIcons.idFor(country.code))
                                        onNavigateBack()
                                    },
                                    onIconClick = { icon ->
                                        onIconSelected(icon.id)
                                        onNavigateBack()
                                    },
                                )
                            }
                        }
                        // Country flags, drawn as emoji
                        viewMode == IconViewMode.FLAGS -> {
                            IconGrid(
                                icons = emptyList(),
                                currentIconId = currentIconId,
                                flags = Countries.all,
                                onFlagClick = { country ->
                                    onIconSelected(FlagIcons.idFor(country.code))
                                    onNavigateBack()
                                },
                                onIconClick = {},
                            )
                        }
                        // Category view
                        viewMode == IconViewMode.CATEGORIES && selectedCategory == null -> {
                            CategoryList(
                                categories = MaterialIconsLibrary.categories,
                                currentIconId = currentIconId,
                                onCategoryClick = { category ->
                                    selectedCategory = category.name
                                },
                            )
                        }
                        // Icons in selected category or all icons
                        displayedIcons.isNotEmpty() -> {
                            Column {
                                selectedCategory?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                }
                                IconGrid(
                                    icons = displayedIcons,
                                    currentIconId = currentIconId,
                                    onIconClick = { icon ->
                                        onIconSelected(icon.id)
                                        onNavigateBack()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        val currentLabel = currentIcon?.let { (id, _) -> MaterialIconsLibrary.getAllIcons().find { it.id == id }?.label ?: id } ?: currentFlag?.label
        if (currentLabel != null) {
            // What the node wears now, in one line at the foot of the page.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = scaffoldPadding.calculateBottomPadding())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    currentIcon != null -> Icon(imageVector = currentIcon.second, contentDescription = null, modifier = Modifier.size(22.dp))
                    currentFlag != null -> FlagGlyph(currentFlag.code, size = 22.dp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                FitText(stringResource(R.string.current_icon_format, currentLabel), MaterialTheme.typography.bodyMedium, minSp = 11f, lines = 2)
            }
        }
    }
}

@Composable
private fun CategoryList(categories: List<IconCategory>, currentIconId: String?, onCategoryClick: (IconCategory) -> Unit) {
    val ink = LocalContentColor.current
    LazyColumn {
        itemsIndexed(categories) { index, category ->
            if (index > 0) Box(Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(0.7.dp).background(ink.copy(alpha = 0.12f)))
            CategoryRow(
                category = category,
                hasSelectedIcon = category.icons.any { it.id == currentIconId },
                onClick = { onCategoryClick(category) },
            )
        }
    }
}

/** A category as a row: its name and size, three of its icons, and a check where the node's icon is. */
@Composable
private fun CategoryRow(category: IconCategory, hasSelectedIcon: Boolean, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (hasSelectedIcon) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = stringResource(R.string.icon_count_format, category.icons.size),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.graphicsLayer { alpha = 0.7f },
            )
        }
        AnimatedCheck(hasSelectedIcon, Modifier.padding(end = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            category.icons.take(3).forEach { icon ->
                Icon(imageVector = icon.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = ink.copy(alpha = 0.7f))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ink.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun IconGrid(
    icons: List<ProfileIcon>,
    currentIconId: String?,
    onIconClick: (ProfileIcon) -> Unit,
    flags: List<Country> = emptyList(),
    onFlagClick: (Country) -> Unit = {},
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 72.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(flags, key = { "flag-" + it.code }) { country ->
            FlagGridItem(
                country = country,
                isSelected = currentIconId == FlagIcons.idFor(country.code),
                onClick = { onFlagClick(country) },
            )
        }
        items(icons) { icon ->
            IconGridItem(
                icon = icon,
                isSelected = currentIconId == icon.id,
                onClick = { onIconClick(icon) },
            )
        }
    }
}

/**
 * One cell of the grid in ink: a faint wash on a bright scene, nothing on a dark one, and a ring of
 * ink round the one the node wears. Its name fits whole, smaller or on two lines.
 */
@Composable
private fun GridCell(label: String, isSelected: Boolean, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    val ink = LocalContentColor.current
    val clear = LocalTransparentUi.current
    val ring by animateFloatAsState(if (isSelected) 1f else 0f, tween(240), label = "cellRing")
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(shape)
            .background(if (clear) Color.Transparent else ink.copy(alpha = 0.05f + 0.05f * ring))
            .border(1.dp, ink.copy(alpha = 0.55f * ring), shape)
            .semantics { selected = isSelected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        glyph()
        Spacer(modifier = Modifier.height(4.dp))
        FitText(
            label,
            MaterialTheme.typography.labelSmall.copy(
                textAlign = TextAlign.Center,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            minSp = 8f,
            lines = 2,
        )
    }
}

@Composable
private fun IconGridItem(icon: ProfileIcon, isSelected: Boolean, onClick: () -> Unit) {
    GridCell(icon.label, isSelected, onClick) {
        Icon(imageVector = icon.icon, contentDescription = null, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun FlagGridItem(country: Country, isSelected: Boolean, onClick: () -> Unit) {
    GridCell(country.label, isSelected, onClick) { FlagGlyph(country.code, size = 30.dp) }
}

@Composable
private fun EmptySearchResult(query: String) {
    io.nekohasekai.sfa.compose.theme.KnineEmptyState(
        title = stringResource(R.string.no_icons_found),
        description = stringResource(R.string.no_icons_match, query),
        icon = Icons.Default.SearchOff,
        modifier = Modifier.fillMaxSize(),
    )
}

private enum class IconViewMode {
    CATEGORIES,
    ALL,
    FLAGS,
    SEARCH,
}
