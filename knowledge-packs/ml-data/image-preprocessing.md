# Image Preprocessing

## When to use
- Building any computer vision pipeline (classification, detection, segmentation, generation)
- Images come from varied sources with different resolutions, formats, and quality
- Preparing data for model training or inference with specific input requirements
- Standardizing a dataset collected over time with changing camera hardware or settings

## Pattern

### Resizing
- **Match model input**: most models expect fixed dimensions (224x224, 384x384, etc.)
- **Preserve aspect ratio**: resize longest edge, then pad (zero-pad or reflect) to square; avoids distortion
- **Center crop vs resize**: crop preserves aspect ratio but loses content; resize includes everything but may distort
- **For detection/segmentation**: resize with coordinate transform; update bounding boxes and masks accordingly
- Use high-quality interpolation (bilinear or bicubic) for downscaling; nearest-neighbor for label masks

### Normalization
- **Channel-wise normalization**: subtract mean, divide by std per channel
  - ImageNet defaults: mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]
  - Use dataset-specific statistics for non-natural-image domains (medical, satellite, microscopy)
- **Scale to [0,1]**: divide by 255; simpler alternative when using models trained this way
- **Scale to [-1,1]**: (pixel / 127.5) - 1; common for GANs
- **Match the pretrained model's normalization exactly** when fine-tuning; check the model card

### Color space
- **RGB**: standard for most deep learning models; verify channel order (RGB vs BGR)
- **Grayscale**: when color is irrelevant (document OCR, X-rays); reduces input dimensions
- **HSV/LAB**: useful for color-based feature engineering or augmentation, rarely as model input
- **DICOM/16-bit**: medical imaging often uses 16-bit grayscale; window/level normalization to relevant intensity range
- Always verify channel ordering: PIL uses RGB, OpenCV uses BGR by default

### Format selection
- **PNG**: lossless, supports transparency; good for labels, masks, and pixel-exact work
- **JPEG**: lossy but much smaller; acceptable for training natural images (quality 85-95)
- **WebP**: better compression than JPEG at similar quality; growing support
- **TIFF**: lossless, supports 16-bit and multi-channel; common in scientific imaging
- **Training-optimized formats**: WebDataset (tar shards), TFRecord, LMDB for fast sequential reads
- Store raw/lossless originals; generate compressed training copies as a pipeline step

### Quality checks
- **Corrupt file detection**: attempt decode on all images; log and remove failures
- **Resolution filtering**: remove images below minimum resolution (e.g., <32x32)
- **Aspect ratio filtering**: extreme ratios (>10:1) may cause issues with standard preprocessing
- **Duplicate detection**: perceptual hashing (pHash, dHash) for near-duplicate image removal
- **EXIF orientation**: apply EXIF rotation on load; some libraries ignore it, causing rotated inputs

### Preprocessing pipeline design
1. Load and validate (decode, check dimensions, verify format)
2. Correct orientation (EXIF)
3. Resize to target dimensions (preserve aspect ratio + pad)
4. Convert color space if needed
5. Normalize pixel values
6. Apply augmentation (training only; see data-augmentation.md)
7. Convert to tensor / model-ready format

### Batch-level considerations
- Use parallel data loading (multi-worker data loaders) to avoid GPU starvation
- Prefetch next batch while current batch trains
- Cache preprocessed images on fast storage (SSD/RAM) for datasets that fit
- For large datasets: preprocess offline and save as shards (WebDataset, TFRecord)

## Gotchas / Anti-patterns
- **Double JPEG compression**: loading a JPEG, processing, saving as JPEG again compounds artifacts; work in lossless in the pipeline
- **Normalization mismatch**: training with ImageNet stats but inferring with [0,1] scaling (or vice versa) silently degrades performance
- **BGR/RGB confusion**: mixing OpenCV (BGR) and PIL/torchvision (RGB) without conversion causes color channel swap
- **Ignoring EXIF rotation**: images appear correctly in viewers (which apply EXIF) but load rotated in code
- **Resizing label masks with bilinear interpolation**: creates invalid intermediate class values; use nearest-neighbor for masks
- **Fixed-size crop on small images**: cropping 224x224 from a 100x100 image fails; resize first, then crop

## References
- torchvision.transforms: standard PyTorch image transforms
- Albumentations: fast, flexible image augmentation and preprocessing
- Pillow (PIL): Python Imaging Library
- OpenCV: computer vision library (watch for BGR default)
- WebDataset: https://github.com/webdataset/webdataset
