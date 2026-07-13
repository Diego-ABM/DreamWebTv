package com.example.dreamweb.managers

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView

class DpadHandler {
    private var cursorX = -1f
    private var cursorY = -1f
    private val step = 25f

    fun handleKeyEvent(event: KeyEvent, webView: WebView, cursorView: View, nativeViews: List<View> = emptyList()): Boolean {
        if (cursorX == -1f) {
            cursorX = webView.width / 2f
            cursorY = webView.height / 2f
            updateCursor(cursorView, webView, nativeViews)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    cursorY += step
                    updateCursor(cursorView, webView, nativeViews)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    cursorY -= step
                    updateCursor(cursorView, webView, nativeViews)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    cursorX -= step
                    updateCursor(cursorView, webView, nativeViews)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    cursorX += step
                    updateCursor(cursorView, webView, nativeViews)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    if (!tryClickNativeView(nativeViews)) {
                        simulateClick(webView, cursorX, cursorY)
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun tryClickNativeView(nativeViews: List<View>): Boolean {
        for (view in nativeViews) {
            if (isCursorOverView(view)) {
                android.util.Log.d("DpadHandler", "Clicking native view: ${view.id}")
                view.performClick()
                return true
            }
        }
        return false
    }

    private fun isCursorOverView(view: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val viewX = location[0].toFloat()
        val viewY = location[1].toFloat()
        
        // Use a slightly larger hitbox for TV convenience
        val padding = 10f
        return cursorX >= (viewX - padding) && cursorX <= (viewX + view.width + padding) &&
               cursorY >= (viewY - padding) && cursorY <= (viewY + view.height + padding)
    }

    private fun updateCursor(cursorView: View, webView: WebView, nativeViews: List<View> = emptyList()) {
        // Clamp cursor within WebView bounds
        cursorX = cursorX.coerceIn(0f, webView.width.toFloat())
        cursorY = cursorY.coerceIn(0f, webView.height.toFloat())

        cursorView.x = cursorX - (cursorView.width / 2)
        cursorView.y = cursorY - (cursorView.height / 2)
        
        // Highlight native views if cursor is over them
        for (view in nativeViews) {
            view.isPressed = isCursorOverView(view)
        }

        // Auto-scroll if cursor reaches edges
        if (cursorY > webView.height - 100) webView.scrollBy(0, 50)
        if (cursorY < 100) webView.scrollBy(0, -50)
    }

    private fun simulateClick(webView: WebView, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()
        
        val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0)
        
        webView.dispatchTouchEvent(downEvent)
        webView.dispatchTouchEvent(upEvent)
        
        downEvent.recycle()
        upEvent.recycle()
    }
}
