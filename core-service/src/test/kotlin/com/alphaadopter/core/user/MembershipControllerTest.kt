package com.alphaadopter.core.user

import com.alphaadopter.core.domain.user.User
import com.alphaadopter.core.domain.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertTrue

@SpringBootTest
class MembershipControllerTest {

    @Autowired
    lateinit var membershipController: MembershipController

    @Autowired
    lateinit var userRepository: UserRepository

    @Test
    @Transactional
    fun `기존 사용자를 회원으로 전환한다`() {
        val user = userRepository.save(User(email = "existing-${System.nanoTime()}@example.com", isMember = false))

        val response = membershipController.activate(MembershipRequest(email = user.email))

        assertTrue(response.isMember)
        assertTrue(userRepository.findByEmail(user.email)!!.isMember)
    }

    @Test
    @Transactional
    fun `존재하지 않는 이메일이면 사용자를 새로 만들어 회원으로 전환한다`() {
        val email = "new-${System.nanoTime()}@example.com"

        val response = membershipController.activate(MembershipRequest(email = email))

        assertTrue(response.isMember)
        assertTrue(userRepository.findByEmail(email)!!.isMember)
    }
}
