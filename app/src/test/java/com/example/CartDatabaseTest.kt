package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.model.CartItem
import com.example.data.local.model.Product
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CartDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun `add item to cart and read it back`() = runBlocking {
        val product = Product(1, "تفاح", "فواكه", 500, "تفاح", "url", "kg")
        db.productDao().insert(product)
        val cartItem = CartItem(productId = 1, quantity = 2)
        db.cartDao().insert(cartItem)
        val items = db.cartDao().getCartItems().first()
        assertEquals(1, items.size)
        assertEquals(2, items[0].quantity)
    }

    @Test
    fun `update item quantity in cart`() = runBlocking {
        val product = Product(1, "تفاح", "فواكه", 500, "تفاح", "url", "kg")
        db.productDao().insert(product)
        val cartItem = CartItem(productId = 1, quantity = 2)
        db.cartDao().insert(cartItem)
        db.cartDao().insert(CartItem(productId = 1, quantity = 5))
        val items = db.cartDao().getCartItems().first()
        assertEquals(1, items.size)
        assertEquals(5, items[0].quantity)
    }
}
