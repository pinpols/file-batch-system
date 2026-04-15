package com.example.batch.common.security;

/** 当 DNS 解析后的 IP 落在受限网段时抛出。 */
public class BlockedAddressException extends RuntimeException {

  public BlockedAddressException(String message) {
    super(message);
  }
}
