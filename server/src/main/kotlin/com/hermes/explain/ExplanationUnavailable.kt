package com.hermes.explain

/** 스펙 §8 — 설명이 없는 것은 안전한 실패다. 사유는 로그에 남고 본문에는 나가지 않는다. */
class ExplanationUnavailableException(val diagnosticReason: String) : RuntimeException(diagnosticReason)
