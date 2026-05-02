package com.modelgate.gateway.validation;

public record ValidationResult(boolean valid, int status, String message) {
  public static ValidationResult ok() {
    return new ValidationResult(true, 200, "OK");
  }

  public static ValidationResult badRequest(String message) {
    return new ValidationResult(false, 400, message);
  }
}
