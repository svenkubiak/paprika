package dtos;

import jakarta.validation.constraints.NotBlank;

public record UpdateAvatarDto(
        @NotBlank(message = "Image is required")
        String image) {
}
