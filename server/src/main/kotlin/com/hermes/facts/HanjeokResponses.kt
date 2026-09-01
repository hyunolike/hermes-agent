package com.hermes.facts

/** 한적은 모든 응답을 이 봉투에 담는다. HTTP 200 에 success=false 가 올 수 있다. */
class HanjeokUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
