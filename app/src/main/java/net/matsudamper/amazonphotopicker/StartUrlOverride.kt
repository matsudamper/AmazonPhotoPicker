package net.matsudamper.amazonphotopicker

import androidx.annotation.VisibleForTesting

/**
 * 計装テストでローカルサーバーのページを開くための差し替え口。
 * Intent で受け付けると他アプリから任意のページを開かせられるため、同一プロセスのテストからのみ設定できるようにする。
 */
object StartUrlOverride {
    @VisibleForTesting
    @Volatile
    var url: String? = null
}
