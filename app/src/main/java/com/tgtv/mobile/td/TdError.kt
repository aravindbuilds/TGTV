package com.tgtv.mobile.td

import org.drinkless.tdlib.TdApi

class TdError(val code: Int, override val message: String) : Exception("TDLib error $code: $message") {
    companion object {
        fun from(e: TdApi.Error) = TdError(e.code, e.message)
    }
}
