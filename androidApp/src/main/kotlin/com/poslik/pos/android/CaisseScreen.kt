package com.poslik.pos.android

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.poslik.pos.core.domain.*
import com.poslik.pos.core.print.TicketRenderer
import com.poslik.pos.core.util.TimeFormat
import kotlinx.coroutines.launch

enum class Screen { CAISSE, HISTORIQUE }

data class UiState(
    val screen: Screen = Screen.CAISSE,
    val cartItems: List<CartLine> = emptyList(),
    val cartCount: Int = 0,
    val cartTotal: Money = Money(0),
    val history: List<HistoryEntry> = emptyList(),
    val lastSale: Sale? = null,
    val online: Boolean = true,
    val unsynced: Int = 0,
    val busy: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaisseScreen(environment: CaisseEnvironment) {
    var state by remember { mutableStateOf(UiState()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCartDetails by remember { mutableStateOf(false) }

    val refreshState = {
        state = state.refresh(environment, environment.service.history(50))
    }

    LaunchedEffect(Unit) {
        environment.service.startBackgroundLoops(intervalMs = 3_000)
        environment.service.onStartup()
        refreshState()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "PosLik",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            environment.storeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    NetworkBadge(
                        online = state.online,
                        unsynced = state.unsynced,
                        busy = state.busy,
                        onClick = {
                            scope.launch {
                                state = state.copy(busy = true)
                                try {
                                    environment.syncNow()
                                } catch (error: Exception) {
                                    snackbar.showSnackbar(
                                        "Synchronisation échouée : " +
                                            (error.message?.take(120) ?: "erreur inconnue"),
                                    )
                                } finally {
                                    state = state.copy(busy = false)
                                    refreshState()
                                }
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Segmented tab navigation
            ScreenTabs(
                current = state.screen,
                onSelect = { state = state.copy(screen = it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            when (state.screen) {
                Screen.CAISSE -> CashierPane(
                    state = state,
                    onAdd = { product ->
                        environment.addToCart(product)
                        refreshState()
                    },
                    onRemove = { product ->
                        environment.removeFromCart(product)
                        refreshState()
                    },
                    onCheckout = { method, given ->
                        scope.launch {
                            val sale = environment.checkout(method, given)
                            refreshState()
                            state = state.copy(lastSale = sale)
                        }
                    },
                    onShowCart = { showCartDetails = true }
                )
                Screen.HISTORIQUE -> HistoryPane(state.history)
            }
        }
    }

    if (showCartDetails) {
        ModalBottomSheet(
            onDismissRequest = { showCartDetails = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            CartDetailsContent(
                items = state.cartItems,
                total = state.cartTotal,
                onClose = { showCartDetails = false }
            )
        }
    }

    state.lastSale?.let { sale ->
        ReceiptDialog(
            sale = sale,
            storeLabel = environment.storeLabel,
            onOk = {
                showCartDetails = false
                state = UiState()
                refreshState()
            },
        )
    }
}

/* ---------------- Tabs ---------------- */

@Composable
private fun ScreenTabs(
    current: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(Modifier.padding(4.dp)) {
            TabButton(
                selected = current == Screen.CAISSE,
                icon = Icons.Default.PointOfSale,
                label = "Caisse",
                onClick = { onSelect(Screen.CAISSE) },
                modifier = Modifier.weight(1f),
            )
            TabButton(
                selected = current == Screen.HISTORIQUE,
                icon = Icons.Default.History,
                label = "Historique",
                onClick = { onSelect(Screen.HISTORIQUE) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TabButton(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            Modifier.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ---------------- Caisse pane ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CashierPane(
    state: UiState,
    onAdd: (Product) -> Unit,
    onRemove: (Product) -> Unit,
    onCheckout: (PaymentMethod, Money?) -> Unit,
    onShowCart: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredProducts = remember(searchQuery) {
        if (searchQuery.isBlank()) ProductCatalog.all
        else ProductCatalog.all.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize()) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Rechercher un produit...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            } else null,
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            )
        )

        // Section header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Produits (${filteredProducts.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }

        // Responsive grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(filteredProducts, key = { it.id }) { product ->
                val qty = state.cartItems.find { it.product.id == product.id }?.quantity ?: 0
                ProductCard(
                    product = product,
                    quantityInCart = qty,
                    onAdd = { onAdd(product) },
                    onRemove = { onRemove(product) }
                )
            }
        }

        // Bottom checkout region
        CheckoutBar(state = state, onCheckout = onCheckout, onShowCart = onShowCart)
    }
}

@Composable
private fun ProductCard(
    product: Product,
    quantityInCart: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onAdd,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (quantityInCart > 0)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (quantityInCart > 0) 2.dp else 1.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.padding(14.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.ShoppingCart,
                        contentDescription = null,
                        tint = if (quantityInCart > 0) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(32.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    product.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    minLines = 1,
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    product.unitPrice.format(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (quantityInCart > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(26.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = quantityInCart.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                IconButton(
                    onClick = { onRemove() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(32.dp)
                ) {
                    Icon(
                        Icons.Default.RemoveCircleOutline,
                        contentDescription = "Retirer",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/* ---------------- Checkout bar ---------------- */

@Composable
private fun CheckoutBar(
    state: UiState,
    onCheckout: (PaymentMethod, Money?) -> Unit,
    onShowCart: () -> Unit,
) {
    val hasItems = state.cartCount > 0

    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            // Cart summary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = hasItems) { onShowCart() }
            ) {
                Icon(
                    Icons.Default.ShoppingCart,
                    contentDescription = null,
                    tint = if (hasItems) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${state.cartCount} article(s)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        state.cartTotal.format(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (hasItems) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { onCheckout(PaymentMethod.CARD, null) },
                    enabled = hasItems,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.CreditCard, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Carte", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { onCheckout(PaymentMethod.CASH, state.cartTotal) },
                    enabled = hasItems,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32),
                    ),
                ) {
                    Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Esp\u00E8ces", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/* ---------------- Cart Details ---------------- */

@Composable
private fun CartDetailsContent(
    items: List<CartLine>,
    total: Money,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .heightIn(max = 400.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "D\u00E9tail du panier",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { line ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(line.product.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${line.quantity} x ${line.product.unitPrice.format()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        line.total.format(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Total",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                total.format(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(Modifier.height(16.dp))
    }
}

/* ---------------- Receipt confirmation ---------------- */

/**
 * Confirmation apres encaissement : aucun imprimante ni apercu PDF, le recu complet est
 * affiche a l'ecran. Fermeture possible uniquement via OK, ce qui ramene la caisse a un
 * panier vide pour la vente suivante.
 */
@Composable
private fun ReceiptDialog(
    sale: Sale,
    storeLabel: String,
    onOk: () -> Unit,
) {
    val receipt = remember(sale) { TicketRenderer(storeLabel, width = 44).render(sale) }

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.CenterHorizontally),
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Re\u00E7u enregistr\u00E9",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "Ticket ${sale.ticketNumber.value} \u00B7 ${sale.total.format()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = receipt,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            maxLines = 200,
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onOk,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/* ---------------- History pane ---------------- */

@Composable
private fun HistoryPane(history: List<HistoryEntry>) {
    if (history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Aucune vente",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Les ventes appara\u00EEtront ici apr\u00E8s encaissement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(history) { entry ->
            HistoryRow(entry)
        }
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.ticketNumber,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${entry.itemCount} article(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    entry.total.format(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (entry.printState) {
                        PrintState.PRINTED -> Icons.Default.Print
                        PrintState.FAILED -> Icons.Default.ErrorOutline
                        else -> Icons.Default.HourglassEmpty
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (entry.printState == PrintState.FAILED) MaterialTheme.colorScheme.error 
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    entry.printStatusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (entry.printState == PrintState.FAILED) MaterialTheme.colorScheme.error 
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(Modifier.width(12.dp))
                
                Icon(
                    when (entry.syncState) {
                        SyncState.SYNCED -> Icons.Default.CloudDone
                        SyncState.FAILED -> Icons.Default.CloudOff
                        SyncState.CONFLICT -> Icons.Default.Warning
                        else -> Icons.Default.CloudQueue
                    },
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = when(entry.syncState) {
                        SyncState.SYNCED -> Color(0xFF2E7D32)
                        SyncState.FAILED -> MaterialTheme.colorScheme.error
                        SyncState.CONFLICT -> Color(0xFFEF6C00)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    entry.syncStatusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = when(entry.syncState) {
                        SyncState.SYNCED -> Color(0xFF2E7D32)
                        SyncState.FAILED -> MaterialTheme.colorScheme.error
                        SyncState.CONFLICT -> Color(0xFFEF6C00)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            
            entry.lastError?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it, 
                    style = MaterialTheme.typography.labelSmall, 
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(Modifier.height(8.dp))
            Text(
                TimeFormat.history(entry.createdAtEpochMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )
        }
    }
}

/* ---------------- Network badge ---------------- */

@Composable
private fun NetworkBadge(
    online: Boolean,
    unsynced: Int,
    busy: Boolean,
    onClick: () -> Unit,
) {
    val container = if (online) Color(0xFF2E7D32) else Color(0xFFC62828)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = container.copy(alpha = 0.12f),
        enabled = !busy,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = container,
                )
            } else {
                Icon(
                    if (online) Icons.Default.CloudQueue else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = container,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = buildString {
                    append(if (online) "En ligne" else "Hors ligne")
                    if (unsynced > 0) append(" ($unsynced)")
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = container,
            )
            if (unsynced > 0 && !busy) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Synchroniser",
                    tint = container,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/* ---------------- state refresh ---------------- */

private fun UiState.refresh(environment: CaisseEnvironment, history: List<HistoryEntry>): UiState {
    val snapshot = environment.cart.snapshot()
    return copy(
        cartCount = snapshot.itemCount,
        cartTotal = snapshot.total,
        cartItems = snapshot.lines,
        history = history,
        online = environment.isOnline(),
        unsynced = environment.service.store.unsyncedCount(),
    )
}
