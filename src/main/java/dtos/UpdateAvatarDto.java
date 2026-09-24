package dtos;

import jakarta.validation.constraints.NotBlank;

/** The profile picture as a base64 data URL, already scaled down by the admin UI. */
public record UpdateAvatarDto(
        @NotBlank(message = "Image is required")
        String image) {
}
