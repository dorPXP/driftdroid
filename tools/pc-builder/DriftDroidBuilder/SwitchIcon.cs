using SkiaSharp;

namespace DriftDroidBuilder;

/// <summary>
/// The picture the Switch's home-brew menu shows for the .nro files. By default it is DriftDroid's own;
/// a player can choose any image of their own (for example their copy of the game's cover). The image is
/// cropped to a square, turned into the 256x256 JPEG the Switch expects and kept next to the other builder
/// settings, so it never leaves this computer and is not part of anything DriftDroid ships.
/// </summary>
public static class SwitchIcon
{
    private const int Size = 256;

    public static string CustomPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "DriftDroidBuilder", "switch-icon.jpg");

    public static bool HasCustom => File.Exists(CustomPath);

    /// <summary>The icon the packaging step uses: the player's, else DriftDroid's own, which is part of the builder
    /// program (so it can change without a new toolkit).</summary>
    public static string Resolve(string toolkitDefault)
    {
        if (HasCustom)
        {
            return CustomPath;
        }
        try
        {
            using var resource = System.Reflection.Assembly.GetExecutingAssembly().GetManifestResourceStream("switch-icon.jpg");
            if (resource != null)
            {
                var path = Path.Combine(Path.GetTempPath(), "driftdroid-builder-switch-icon.jpg");
                using var file = File.Create(path);
                resource.CopyTo(file);
                return path;
            }
        }
        catch (Exception)
        {
            // fall back to the picture the toolkit carries
        }
        return toolkitDefault;
    }

    /// <summary>Converts the image at <paramref name="source"/> and makes it the Switch icon.
    /// Returns null on success, otherwise what to tell the player.</summary>
    public static string? Set(string source)
    {
        try
        {
            using var input = File.OpenRead(source);
            using var original = SKBitmap.Decode(input);
            if (original == null || original.Width < 1 || original.Height < 1)
            {
                return "That file is not a picture the builder can read. Use a PNG or JPG.";
            }
            // Centre square, so a tall cover keeps its middle rather than being squashed.
            var side = Math.Min(original.Width, original.Height);
            var crop = new SKRectI((original.Width - side) / 2, (original.Height - side) / 2,
                (original.Width - side) / 2 + side, (original.Height - side) / 2 + side);
            using var surface = SKSurface.Create(new SKImageInfo(Size, Size, SKColorType.Rgba8888, SKAlphaType.Premul));
            var canvas = surface.Canvas;
            // JPEG has no transparency: a see-through picture goes onto black.
            canvas.Clear(SKColors.Black);
            using var image = SKImage.FromBitmap(original);
            using var paint = new SKPaint { FilterQuality = SKFilterQuality.High, IsAntialias = true };
            canvas.DrawImage(image, crop, new SKRect(0, 0, Size, Size), paint);
            using var result = surface.Snapshot();
            using var jpeg = result.Encode(SKEncodedImageFormat.Jpeg, 92);
            Directory.CreateDirectory(Path.GetDirectoryName(CustomPath)!);
            using var output = File.Create(CustomPath);
            jpeg.SaveTo(output);
            return null;
        }
        catch (Exception e)
        {
            return $"The picture could not be used: {e.Message}";
        }
    }

    public static void UseDefault()
    {
        try
        {
            File.Delete(CustomPath);
        }
        catch (Exception)
        {
            // an icon file that cannot be removed is not worth stopping for
        }
    }
}
