package com.alphaadopter.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class CoreServiceApplication

fun main(args: Array<String>) {
	runApplication<CoreServiceApplication>(*args)
}
