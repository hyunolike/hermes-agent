package com.hermes

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulith

@Modulith
@SpringBootApplication
class HermesApplication

fun main(args: Array<String>) {
    runApplication<HermesApplication>(*args)
}
