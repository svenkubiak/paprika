package dtos;

public record UserUpdateDto(
        String username,
        String email,
        String password) {
}
