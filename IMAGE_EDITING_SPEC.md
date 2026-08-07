# Image Editing v1

Flow:
1. select an image from the campaign visual library;
2. type an edit instruction;
3. Android uploads the selected image to the secure RPG OS backend;
4. backend calls the OpenAI image edit endpoint;
5. edited PNG is returned;
6. Android saves it as a new image in `Pictures/RPG OS`;
7. campaign DB records `source_visual_uid`.

The original image is never overwritten.
