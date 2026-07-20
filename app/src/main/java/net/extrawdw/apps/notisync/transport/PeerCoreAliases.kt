package net.extrawdw.apps.notisync.transport

typealias DeliveryMode = net.extrawdw.notisync.peer.transport.DeliveryMode
typealias AuthTokenStore = net.extrawdw.notisync.peer.transport.AuthTokenStore
typealias BrokerClient = net.extrawdw.notisync.peer.transport.BrokerClient

fun DeliveryMode.ifKnown(): DeliveryMode? =
    takeUnless { it == DeliveryMode.UNKNOWN }
