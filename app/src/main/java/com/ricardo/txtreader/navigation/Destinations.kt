package com.ricardo.txtreader.navigation

sealed class Destination(val route: String) {
    data object Library : Destination("library")
    data object Reader : Destination("reader")
}
