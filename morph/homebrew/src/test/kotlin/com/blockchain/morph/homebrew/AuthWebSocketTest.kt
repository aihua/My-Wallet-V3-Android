package com.blockchain.morph.homebrew

import com.blockchain.nabu.Authenticator
import com.blockchain.nabu.models.NabuSessionTokenResponse
import com.blockchain.network.websocket.ConnectionEvent
import com.blockchain.network.websocket.WebSocket
import com.blockchain.network.websocket.WebSocketConnection
import com.blockchain.network.websocket.WebSocketSendReceive
import com.blockchain.network.websocket.plus
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.never
import com.nhaarman.mockito_kotlin.verify
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import org.amshove.kluent.`it returns`
import org.amshove.kluent.`should equal`
import org.amshove.kluent.mock
import org.junit.Test

class AuthWebSocketTest {

    private val authenticator = mock<Authenticator> {
        on { authenticate() } `it returns` Single.just(nabuSessionTokenResponse("TheToken"))
    }

    private fun givenAuthenticatedSocket(
        underlyingSocket: MockSendReceive,
        connection: MockConnection
    ): WebSocket<String, String> =
        (underlyingSocket + connection).authenticate(authenticator)

    @Test
    fun `after a successful connection, the token from authenticator is sent to the socket`() {
        val connection = MockConnection()
        val underlyingSocket = MockSendReceive()
        val socket: WebSocket<String, String> = givenAuthenticatedSocket(underlyingSocket, connection)

        socket.open()
        verify(underlyingSocket.mock, never()).send(any())
        connection.simulateSuccess()
        verify(underlyingSocket.mock)
            .send(
                "{\"action\":\"subscribe\"," +
                    "\"channel\":\"auth\"," +
                    "\"params\":{" +
                    "\"token\":\"TheToken\"," +
                    "\"type\":\"auth\"" +
                    "}}"
            )
    }

    @Test
    fun `after a successful auth, a new connection event is fired`() {
        val connection = MockConnection()
        val underlyingSocket = MockSendReceive()
        val socket: WebSocket<String, String> = givenAuthenticatedSocket(underlyingSocket, connection)

        socket.open()
        val connectionEvents = socket.connectionEvents.test()
        connection.simulateSuccess()
        underlyingSocket.simulateResponse(
            """
{
  "seqnum": 0,
  "channel": "auth",
  "event": "subscribed"
}
        """
        )
        connectionEvents.values() `should equal` listOf(ConnectionEvent.Connected, ConnectionEvent.Authenticated)
    }

    @Test
    fun `after a unsuccessful auth, the connection event is not fired`() {
        val connection = MockConnection()
        val underlyingSocket = MockSendReceive()
        val socket: WebSocket<String, String> = givenAuthenticatedSocket(underlyingSocket, connection)

        socket.open()
        val connectionEvents = socket.connectionEvents.test()
        connection.simulateSuccess()
        simulateAuthFailure(underlyingSocket)
        connectionEvents.values() `should equal` listOf(
            ConnectionEvent.Connected,
            ConnectionEvent.Failure(AuthenticationException("Can not process auth request, token can not be found"))
        )
    }

    @Test
    fun `after a unsuccessful auth, the token is refreshed`() {
        val connection = MockConnection()
        val underlyingSocket = MockSendReceive()
        val socket: WebSocket<String, String> = givenAuthenticatedSocket(underlyingSocket, connection)

        socket.open()
        connection.simulateSuccess()
        simulateAuthFailure(underlyingSocket)
        verify(authenticator).invalidateToken()
    }

    private fun simulateAuthFailure(underlyingSocket: MockSendReceive) {
        underlyingSocket.simulateResponse(
            """
    {
      "seqnum": 0,
      "channel": "auth",
      "event": "error",
      "description": "Can not process auth request, token can not be found"
    }
            """
        )
    }

    @Test
    fun `receiving other json does not crash the class`() {
        val connection = MockConnection()
        val underlyingSocket = MockSendReceive()
        val socket: WebSocket<String, String> = givenAuthenticatedSocket(underlyingSocket, connection)

        socket.open()
        val connectionEvents = socket.connectionEvents.test()
        connection.simulateSuccess()
        underlyingSocket.simulateResponse(
            """
{
  "some": "other json"
}
        """
        )
        connectionEvents.values() `should equal` listOf(ConnectionEvent.Connected)
    }
}

private fun nabuSessionTokenResponse(
    token: String
): NabuSessionTokenResponse {
    return NabuSessionTokenResponse(
        id = "",
        userId = "",
        token = token,
        isActive = true,
        expiresAt = "",
        insertedAt = "",
        updatedAt = ""
    )
}

private class MockSendReceive : WebSocketSendReceive<String, String> {

    val mock: WebSocketSendReceive<String, String> = mock()

    private val responseSubject = PublishSubject.create<String>()

    override fun send(message: String) {
        mock.send(message)
    }

    fun simulateResponse(message: String) {
        responseSubject.onNext(message)
    }

    override val responses: Observable<String>
        get() = responseSubject
}

private class MockConnection(
    val mock: WebSocketConnection = mock()
) : WebSocketConnection by mock {

    private val subject: Subject<ConnectionEvent> = PublishSubject.create<ConnectionEvent>()

    override val connectionEvents: Observable<ConnectionEvent>
        get() = subject

    fun simulateSuccess() {
        subject.onNext(ConnectionEvent.Connected)
    }
}
